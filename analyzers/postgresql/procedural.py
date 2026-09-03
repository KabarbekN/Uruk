"""Structural PL/pgSQL facts from libpg_query's native procedural parse tree."""

from pglast import ast, parse_plpgsql, parse_sql, parser

from source_maps import BodyLocations, function_body
from sql_ranges import SqlRanges


def expression(value):
    return value.get("PLpgSQL_expr", {}).get("query") if isinstance(value, dict) else None


class Procedural:
    def __init__(self, extractor, key, region, body):
        self.ex = extractor
        self.function = key
        self.region = function_body(region, body)
        self.locations = BodyLocations(self.region)
        self.definition = region
        self.variables = {}

    def emit(self, kind, key, name, owner, region, **properties):
        return self.ex.emit(kind, key, name, owner, region.evidence(), sourceFormat="PLPGSQL",
                            evidencePrecision="ORIGINAL_SOURCE_SPAN" if region.linear else "ENCLOSING_LITERAL",
                            controlFlowResolution="STRUCTURAL", **properties)

    def extract(self):
        functions = parse_plpgsql(self.definition.text)
        for wrapper in functions:
            function = wrapper.get("PLpgSQL_function", {})
            for index, item in enumerate(function.get("datums", [])):
                datum = next(iter(item.values()))
                name = datum.get("refname") or datum.get("fieldname")
                self.variables[index] = name
                if not datum.get("lineno") or not name:
                    continue
                region = self.locations.statement(datum["lineno"], {name.upper(), '"' + name.replace('"', '""').upper() + '"'}, advance=False)
                key = self.ex.unique(self.function, "variable:" + name)
                self.emit("PARAMETER", key, name, self.function, region, parameterKind="LOCAL_VARIABLE",
                          declaredType=datum.get("datatype", {}).get("PLpgSQL_type", {}).get("typname"))
                if datum.get("default_val"):
                    self.emit("DATA_TRANSFORMATION", key + "/initializer", "INITIALIZE", key, region,
                              targetVariable=name, expression=expression(datum["default_val"]))
            action = function.get("action")
            if action:
                self.visit(action, self.function)

    def sequence(self, items, owner):
        first, previous = None, None
        for item in items or []:
            result = self.visit(item, owner)
            if result:
                key, evidence = result
                if previous:
                    self.ex.edge(previous, key, "PRECEDES", evidence)
                first = first or result
                previous = key
        return first

    def branch(self, condition, branch, items, owner, region):
        guards = list(self.ex.context.get("guardedByConditions", []))
        guards.append({"conditionKey": condition, "branch": branch})
        with self.ex.contextual(None, {"guardedByConditions": guards}):
            first = self.sequence(items, owner)
        if first:
            self.ex.edge(condition, first[0], "BRANCH_" + branch, region.evidence())
        return first

    def sql(self, query, region, owner, statement_kind):
        if not query:
            return None
        previous = self.ex.ranges, self.ex.bounds
        try:
            self.ex.ranges = SqlRanges(region.decoded(query))
            self.ex.bounds = (0, len(query))
            first = None
            for raw in parse_sql(query):
                if not isinstance(raw.stmt, (ast.SelectStmt, ast.InsertStmt, ast.UpdateStmt, ast.DeleteStmt, ast.MergeStmt)):
                    self.ex.unknown("Procedural utility SQL is parsed but its effects are not interpreted", region.evidence(), "PROCEDURAL_SQL_EFFECTS")
                    continue
                with self.ex.contextual(None, {"sourceFormat": "PLPGSQL", "statementKind": statement_kind,
                                               "normalizedBy": "PLPGSQL_PARSER"}):
                    key = self.ex.query(raw.stmt, owner, region.evidence())
                    first = first or key
            return first
        except parser.ParseError as error:
            self.ex.unknown(f"Procedural SQL cannot be parsed independently: {error}", region.evidence(), "PROCEDURAL_SQL_BINDINGS")
            return None
        finally:
            self.ex.ranges, self.ex.bounds = previous

    def visit(self, wrapper, owner):
        self.ex.run.tick()
        if not isinstance(wrapper, dict) or len(wrapper) != 1:
            self.ex.unknown("Unexpected native procedural AST shape", self.region.evidence(), "PROCEDURAL_AST")
            return None
        native, data = next(iter(wrapper.items()))
        kind = native.removeprefix("PLpgSQL_")
        line = data.get("lineno")
        if kind == "stmt_block":
            if line:
                region = self.locations.statement(line, {"BEGIN"}, "BEGIN")
                key = self.ex.unique(owner, "block")
                self.emit("CONTROL_FLOW", key, "BLOCK", owner, region)
                nested_owner = key
            else:
                region, key, nested_owner = self.region, owner, owner
            first = self.sequence(data.get("body"), nested_owner)
            exceptions = data.get("exceptions", {}).get("PLpgSQL_exception_block", {}).get("exc_list", [])
            for exception in exceptions:
                value = exception.get("PLpgSQL_exception", {})
                handler = self.ex.unique(key, "exception-handler")
                conditions = [c.get("PLpgSQL_condition", {}).get("condname") for c in value.get("conditions", [])]
                # Native handlers have no location; retain the actual enclosing body span.
                self.emit("EXCEPTION", handler, "EXCEPTION_HANDLER", key, self.region, conditions=conditions,
                          evidenceScope="ENCLOSING_FUNCTION_BODY")
                self.ex.edge(self.function, handler, "HANDLED_BY", self.region.evidence())
                self.sequence(value.get("action"), handler)
            return (key, region.evidence()) if line else first
        if not line:
            # libpg_query adds implicit returns; they have no source evidence.
            if kind == "stmt_return" and not data:
                return None
            self.ex.unknown(f"{native} has no native source position", self.region.evidence(), "PROCEDURAL_SOURCE_RANGE")
            return None
        key = self.ex.unique(owner, kind)
        if kind in {"stmt_if", "if_elsif"}:
            word = "IF" if kind == "stmt_if" else "ELSIF"
            region = self.locations.statement(line, {word}, "THEN")
            self.emit("CONDITION", key, word, owner, region, expression=expression(data.get("cond")))
            self.branch(key, "TRUE", data.get("then_body", data.get("stmts", [])), key, region)
            previous = key
            for elsif in data.get("elsif_list", []):
                result = self.visit(elsif, owner)
                if result:
                    self.ex.edge(previous, result[0], "BRANCH_FALSE", result[1])
                    previous = result[0]
            self.branch(previous, "FALSE", data.get("else_body"), key, region)
        elif kind in {"stmt_loop", "stmt_while", "stmt_fori", "stmt_fors", "stmt_foreach_a", "stmt_dynfors"}:
            words = {"stmt_loop": {"LOOP"}, "stmt_while": {"WHILE"}, "stmt_foreach_a": {"FOREACH"}}.get(kind, {"FOR"})
            region = self.locations.statement(line, words, "LOOP")
            self.emit("LOOP", key, kind.removeprefix("stmt_").upper(), owner, region,
                      expression=expression(data.get("cond")), lower=expression(data.get("lower")),
                      upper=expression(data.get("upper")), step=expression(data.get("step")), reverse=bool(data.get("reverse")),
                      arrayExpression=expression(data.get("expr")))
            if kind == "stmt_fors":
                self.sql(expression(data.get("query")), region, key, "FOR_QUERY")
            elif kind == "stmt_dynfors":
                self.ex.unknown("Dynamic FOR query target is unknown", region.evidence(), "DYNAMIC_SQL")
            self.branch(key, "TRUE", data.get("body"), key, region)
        elif kind == "stmt_assign":
            region = self.locations.statement(line)
            self.emit("DATA_TRANSFORMATION", key, "ASSIGN", owner, region,
                      targetVariable=self.variables.get(data.get("varno")), expression=expression(data.get("expr")),
                      transformationKind="PLPGSQL_ASSIGNMENT")
        elif kind in {"stmt_execsql", "stmt_perform"}:
            query = expression(data.get("sqlstmt" if kind == "stmt_execsql" else "expr"))
            words = {"PERFORM"} if kind == "stmt_perform" else {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "CREATE", "ALTER", "DROP", "TRUNCATE"}
            region = self.locations.statement(line, words)
            result = self.sql(query, region, owner, kind.removeprefix("stmt_").upper())
            if result:
                return result, region.evidence()
            self.emit("CONTROL_FLOW", key, "SQL_STATEMENT", owner, region, expression=query)
            self.ex.unknown("Procedural utility SQL effects are not resolved", region.evidence(), "PROCEDURAL_SQL_EFFECTS")
        elif kind in {"stmt_return", "stmt_return_next", "stmt_return_query"}:
            region = self.locations.statement(line, {"RETURN"})
            self.emit("RETURN_OUTCOME", key, kind.removeprefix("stmt_").upper(), owner, region,
                      expression=expression(data.get("expr")) or self.variables.get(data.get("retvarno")))
            self.ex.edge(self.function, key, "RETURNS", region.evidence())
            if data.get("dynquery"):
                self.ex.unknown("Dynamic RETURN QUERY target is unknown", region.evidence(), "DYNAMIC_SQL")
            elif data.get("query"):
                self.sql(expression(data["query"]), region, key, "RETURN_QUERY")
        elif kind == "stmt_raise":
            region = self.locations.statement(line, {"RAISE"})
            self.emit("EXCEPTION", key, "RAISE", owner, region, message=data.get("message"),
                      conditionName=data.get("condname"), errorLevel=data.get("elog_level"),
                      arguments=[expression(value) for value in data.get("params", [])])
            self.ex.edge(owner, key, "THROWS", region.evidence())
        elif kind == "stmt_exit":
            region = self.locations.statement(line, {"EXIT", "CONTINUE"})
            self.emit("CONTROL_FLOW", key, "EXIT" if data.get("is_exit") else "CONTINUE", owner, region,
                      expression=expression(data.get("cond")), label=data.get("label"))
        elif kind == "stmt_dynexecute":
            region = self.locations.statement(line, {"EXECUTE"})
            self.emit("CALL", key, "EXECUTE", owner, region, expression=expression(data.get("query")),
                      targetResolution="UNKNOWN_DYNAMIC_SQL", parameters=[expression(value) for value in data.get("params", [])])
            self.ex.unknown("Dynamic SQL is recorded without inventing a read/write target", region.evidence(), "DYNAMIC_SQL")
        elif kind == "stmt_call":
            region = self.locations.statement(line, {"CALL", "DO"})
            self.emit("CALL", key, "CALL", owner, region, expression=expression(data.get("expr")), targetResolution="UNRESOLVED")
            self.ex.unknown("Procedure call overload/effects require resolution", region.evidence(), "PROCEDURE_CALL_RESOLUTION")
        else:
            region = self.locations.line_region(line)
            self.emit("CONTROL_FLOW", key, native, owner, region, statementKind=native)
            self.ex.unknown(f"{native} is parsed but not interpreted", region.evidence(), "PROCEDURAL_STATEMENT")
        return key, region.evidence()
