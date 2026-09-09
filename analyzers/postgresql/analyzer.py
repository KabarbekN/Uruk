"""PostgreSQL migration and query facts derived from PostgreSQL's real parser."""

from collections import defaultdict
from contextlib import contextmanager
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "shared"))
from runtime import cli, Rejected
from pglast import ast, parse_sql, parser
from pglast.stream import RawStream
from source_maps import Region, function_body
from sql_ranges import SqlRanges

DESCRIPTOR = {"id": "postgresql", "version": "0.1.0", "contractVersion": "1.0",
              "languages": ["SQL"], "frameworks": ["POSTGRESQL", "FLYWAY", "LIQUIBASE"],
              "capabilities": ["SCHEMAS", "TABLES", "COLUMNS", "DEFAULTS", "CONSTRAINTS", "INDEXES", "TRIGGERS", "POLICIES", "VIEWS", "FUNCTIONS", "DATABASE_ACCESS", "DATA_OWNERSHIP"],
              "executionMode": "SAFE_STATIC",
              "metadata": {"supportsIncremental": False, "requiresDependencyResolution": False, "networkPolicy": "DENY"}}


def render(node):
    if node is None:
        return None
    try:
        return RawStream()(node)
    except Exception:
        return str(node)


def names(nodes):
    return [node.sval for node in nodes or ()]


def qualified(relation):
    return ".".join(p for p in (relation.catalogname, relation.schemaname, relation.relname) if p)


def walk(node):
    pending = [node]
    while pending:
        current = pending.pop()
        if isinstance(current, ast.Node):
            yield current
            pending.extend(reversed([getattr(current, field) for field in current]))
        elif isinstance(current, (list, tuple)):
            pending.extend(reversed(current))


class Extractor:
    def __init__(self, run, source):
        self.run, self.source = run, source
        self.file_key = "file:" + source.path
        self.sequence = defaultdict(int)
        self.scope = self.file_key
        self.region = Region.whole(source)
        self.statement_region = self.region
        self.ranges = None
        self.bounds = (0, len(source.text))
        self.context = {}
        self.failed = False

    @contextmanager
    def contextual(self, scope=None, properties=None):
        old_scope, old_context = self.scope, self.context
        self.scope = scope or old_scope
        self.context = {**old_context, **(properties or {})}
        try:
            yield
        finally:
            self.scope, self.context = old_scope, old_context

    def emit(self, kind, key, name, owner, evidence, origin="DATABASE_DERIVED", confidence=0.98, **properties):
        self.run.fact(kind, key, name, owner, evidence, origin, confidence,
                      **{"sourceLayer": "DATABASE", **self.context, **properties})
        if owner:
            self.run.relation(owner, key, "CONTAINS", evidence, origin, confidence)
        for guard in self.context.get("guardedByConditions", []):
            self.edge(key, guard["conditionKey"], "GUARDED_BY", evidence)
        return key

    def edge(self, source, target, kind, evidence):
        self.run.relation(source, target, kind, evidence, "DATABASE_DERIVED", 0.98)

    def unknown(self, message, evidence, capability="SQL_SEMANTICS"):
        self.run.unknown.add(capability)
        self.run.diagnostic("PARTIAL_SQL_COVERAGE", message, self.source.path, evidence["startLine"])

    def unique(self, owner, kind):
        key = owner + "/" + kind + "@" + self.source.path
        index = self.sequence[key]
        self.sequence[key] += 1
        return f"{key}:{index}"

    def constraint(self, node, table, column, evidence):
        constraint = node.contype.name.removeprefix("CONSTR_")
        owner = column or table
        ownership = "DATABASE_DEFAULT" if constraint == "DEFAULT" else "DATABASE_GENERATED" if constraint in {"GENERATED", "IDENTITY"} else "DATABASE_CONSTRAINT"
        key = self.unique(owner, constraint.lower())
        props = {"ruleType": constraint, "ownership": ownership, "expression": render(node.raw_expr),
                 "columns": names(node.keys or node.fk_attrs), "constraintName": node.conname,
                 "deferrable": node.deferrable, "initiallyDeferred": node.initdeferred}
        if node.pktable:
            props["referencedTable"] = qualified(node.pktable)
            props["referencedColumns"] = names(node.pk_attrs)
            self.edge(owner, "db:table:" + qualified(node.pktable), "DEPENDS_ON", evidence)
        self.emit("DATA_RULE", key, node.conname or constraint, owner, evidence,
                  subject={"kind": "COLUMN" if column else "TABLE", "stableKey": owner}, **props)
        self.edge(owner, key, "OWNED_BY", evidence)

    def column(self, node, table, evidence):
        colname = getattr(node, "colname", None) or "anonymous"
        key = table.replace("db:table:", "db:column:", 1) + "." + colname
        self.emit("COLUMN", key, colname, table, evidence, dataType=render(getattr(node, "typeName", None)),
                  nullable=not any(c.contype.name in {"CONSTR_NOTNULL", "CONSTR_PRIMARY"} for c in getattr(node, "constraints", None) or ()))
        for constraint in getattr(node, "constraints", None) or ():
            self.constraint(constraint, table, key, evidence)

    def query(self, node, owner, evidence):
        """Visit each query scope separately so subqueries and CTEs retain their own owners."""
        query_types = (ast.SelectStmt, ast.InsertStmt, ast.UpdateStmt, ast.DeleteStmt, ast.MergeStmt)
        pending = [(node, owner, frozenset())]
        first = None
        direct_query = None
        query_keys = set()
        while pending:
            current, parent, inherited_ctes = pending.pop()
            self.run.tick()
            if isinstance(current, query_types):
                operation = type(current).__name__.removesuffix("Stmt").upper()
                write = not isinstance(current, ast.SelectStmt)
                key = self.unique(parent, operation.lower())
                query_keys.add(key)
                first = first or key
                if current is node:
                    direct_query = key
                self.emit("DATABASE_WRITE" if write else "DATABASE_READ", key, operation, parent, evidence,
                          operation=operation, expression=render(current)[:8000])
                with_clause = getattr(current, "withClause", None)
                ctes = inherited_ctes | frozenset(c.ctename for c in with_clause.ctes if c.ctename) if with_clause else inherited_ctes
                target = getattr(current, "relation", None)
                if target:
                    self.edge(key, "db:table:" + qualified(target), "WRITES", evidence)
                for field, kind, edge in (("whereClause", "FILTER", "FILTERS"), ("havingClause", "FILTER", "FILTERS"),
                                          ("sortClause", "SORT", "SORTS"), ("groupClause", "AGGREGATION", "AGGREGATES"),
                                          ("targetList", "DATA_TRANSFORMATION", "TRANSFORMS")):
                    value = getattr(current, field, None)
                    if value:
                        detail = self.unique(key, field)
                        clause_evidence = self.ranges.clause(field, self.bounds, evidence) if self.ranges and current is node else evidence
                        self.emit(kind, detail, field, key, clause_evidence, expression=render(value),
                                  transformationKind="GROUP_BY" if field == "groupClause" else field)
                        self.edge(key, detail, edge, clause_evidence)
                for target in getattr(current, "targetList", None) or ():
                    if isinstance(target, ast.ResTarget):
                        self.emit("DATA_TRANSFORMATION", self.unique(key, "projection"), target.name or "EXPRESSION", key,
                                  self.ranges.clause("targetList", self.bounds, evidence) if self.ranges and current is node else evidence,
                                  expression=render(target.val), targetColumn=target.name, expressionKind=type(target.val).__name__)
                for field in reversed(tuple(current)):
                    value = getattr(current, field)
                    if field != "relation":
                        pending.append((value, key, ctes))
            elif isinstance(current, ast.RangeVar):
                if parent in query_keys and (current.relname not in inherited_ctes or current.schemaname):
                    self.edge(parent, "db:table:" + qualified(current), "READS", evidence)
            elif isinstance(current, ast.JoinExpr):
                key = self.unique(parent, "join")
                join_evidence = self.ranges.clause("fromClause", self.bounds, evidence) if self.ranges and parent == direct_query else evidence
                self.emit("DATA_TRANSFORMATION", key, "JOIN", parent, join_evidence, joinType=current.jointype.name,
                          expression=render(current.quals), using=names(current.usingClause), natural=current.isNatural)
                self.edge(parent, key, "TRANSFORMS", join_evidence)
                pending.extend((getattr(current, field), parent, inherited_ctes) for field in reversed(tuple(current)))
            elif isinstance(current, ast.FuncCall):
                function = ".".join(names(current.funcname))
                key = self.unique(parent, "function-call")
                aggregate = bool(current.agg_star or current.agg_distinct or current.agg_order or current.agg_filter or current.agg_within_group)
                call_evidence = self.ranges.function(current, evidence) if self.ranges else evidence
                self.emit("AGGREGATION" if aggregate else "DATA_TRANSFORMATION", key, function, parent, call_evidence, expression=render(current),
                          functionName=function, aggregateSyntax=aggregate, distinct=current.agg_distinct,
                          aggregationOrder=render(current.agg_order), filter=render(current.agg_filter),
                          window=render(current.over), targetResolution="UNRESOLVED_WITHOUT_CATALOG")
                self.edge(parent, key, "AGGREGATES" if aggregate else "TRANSFORMS", call_evidence)
                pending.extend((getattr(current, field), parent, inherited_ctes) for field in reversed(tuple(current)))
            elif isinstance(current, ast.Node):
                pending.extend((getattr(current, field), parent, inherited_ctes) for field in reversed(tuple(current)))
            elif isinstance(current, (tuple, list)):
                pending.extend((item, parent, inherited_ctes) for item in reversed(current))
        return first

    def function(self, node, evidence):
        name = ".".join(names(node.funcname))
        signature = ",".join(render(p.argType) for p in node.parameters or ())
        key = f"db:function:{name}({signature})"
        options = {option.defname: option.arg for option in node.options or ()}
        language = getattr(options.get("language"), "sval", None)
        bodies = names(options.get("as")) if isinstance(options.get("as"), tuple) else []
        self.emit("DATABASE_FUNCTION", key, name, self.scope, evidence, language=language,
                  returns=render(node.returnType), procedure=node.is_procedure, signature=signature)
        if node.sql_body:
            self.query(node.sql_body, key, evidence)
        elif language == "sql" and bodies:
            try:
                body_region = function_body(self.statement_region, bodies[0])
                with self.contextual(key, {"sourceFormat": "SQL_FUNCTION_BODY"}):
                    self.sql(bodies[0], region=body_region)
            except parser.ParseError:
                self.unknown("SQL function body could not be parsed", evidence, "FUNCTION_BODIES")
        elif language == "plpgsql" and bodies:
            try:
                from procedural import Procedural
                Procedural(self, key, self.statement_region, bodies[0]).extract()
            except (parser.ParseError, ValueError) as error:
                self.failed = True
                self.run.unknown.add("MALFORMED_PLPGSQL")
                self.run.diagnostic("PLPGSQL_PARSE_ERROR", str(error), self.source.path, evidence["startLine"], severity="ERROR")
        else:
            self.unknown(f"Function language {language or 'unknown'} body is not analyzed", evidence, "FUNCTION_BODIES")

    def statement(self, node, evidence):
        if isinstance(node, ast.CreateStmt):
            table = "db:table:" + qualified(node.relation)
            self.emit("TABLE", table, qualified(node.relation), self.scope, evidence,
                      schema=node.relation.schemaname, schemaResolution="EXPLICIT" if node.relation.schemaname else "UNQUALIFIED",
                      operation="CREATE", ifNotExists=node.if_not_exists)
            for element in node.tableElts or ():
                if isinstance(element, ast.ColumnDef):
                    self.column(element, table, evidence)
                elif isinstance(element, ast.Constraint):
                    self.constraint(element, table, None, evidence)
            if node.inhRelations:
                self.unknown("Inherited column definitions require catalog resolution", evidence, "INHERITANCE")
        elif isinstance(node, ast.AlterTableStmt):
            table = "db:table:" + qualified(node.relation)
            for command in node.cmds:
                definition = command.def_
                if isinstance(definition, ast.ColumnDef):
                    self.column(definition, table, evidence)
                elif isinstance(definition, ast.Constraint):
                    self.constraint(definition, table, None, evidence)
                elif command.subtype.name == "AT_ColumnDefault":
                    column = table.replace("db:table:", "db:column:", 1) + "." + command.name
                    key = self.unique(column, "alter-default")
                    self.emit("DATA_RULE", key, "DEFAULT", column, evidence, ruleType="DEFAULT",
                              ownership="DATABASE_DEFAULT", expression=render(definition), operation="DROP" if definition is None else "SET")
                    self.edge(column, key, "OWNED_BY", evidence)
                elif "RowSecurity" in command.subtype.name:
                    self.emit("SECURITY_RULE", self.unique(table, "rls"), command.subtype.name, table, evidence,
                              ownership="DATABASE_POLICY", operation=command.subtype.name)
                else:
                    self.emit("DATA_RULE", self.unique(table, "alter"), command.subtype.name, table, evidence,
                              operation=command.subtype.name, column=command.name, expression=render(command))
                    self.unknown("ALTER TABLE operation is recorded without replaying catalog state", evidence, "SCHEMA_REPLAY")
        elif isinstance(node, ast.CreateSchemaStmt):
            schema = "db:schema:" + (node.schemaname or "unknown")
            self.emit("MODULE", schema, node.schemaname or "unknown", self.scope, evidence, databaseKind="SCHEMA")
            for element in node.schemaElts or ():
                self.statement(element, evidence)
        elif isinstance(node, ast.IndexStmt):
            table = "db:table:" + qualified(node.relation)
            self.emit("DATA_RULE", self.unique(table, "index:" + (node.idxname or "anonymous")), node.idxname or "INDEX", table,
                      evidence, ruleType="INDEX", ownership="DATABASE_CONSTRAINT" if node.unique else None,
                      unique=node.unique, accessMethod=node.accessMethod, expression=render(node), filter=render(node.whereClause))
        elif isinstance(node, ast.CreateTrigStmt):
            table = "db:table:" + qualified(node.relation)
            key = table + "/trigger:" + node.trigname
            function = ".".join(names(node.funcname))
            events = [label for flag, label in ((4, "INSERT"), (8, "DELETE"), (16, "UPDATE"), (32, "TRUNCATE")) if node.events & flag]
            self.emit("DATABASE_TRIGGER", key, node.trigname, table, evidence, ownership="DATABASE_TRIGGER",
                      functionName=function, events=events, timing="INSTEAD_OF" if node.timing & 64 else "BEFORE" if node.timing & 2 else "AFTER",
                      rowLevel=node.row, condition=render(node.whenClause), columns=names(node.columns))
            self.edge(table, key, "TRIGGERS", evidence)
            self.edge(table, key, "OWNED_BY", evidence)
            self.edge(key, f"db:function:{function}()", "CALLS", evidence)
        elif isinstance(node, (ast.CreatePolicyStmt, ast.AlterPolicyStmt)):
            table = "db:table:" + qualified(node.table)
            key = table + "/policy:" + node.policy_name
            self.emit("AUTHORIZATION_RULE", key, node.policy_name, table, evidence, ownership="DATABASE_POLICY",
                      command=getattr(node, "cmd_name", None), using=render(node.qual), withCheck=render(node.with_check),
                      roles=[role.rolename or role.roletype.name for role in node.roles or ()],
                      permissive=getattr(node, "permissive", None), requiresRowLevelSecurity=True)
            self.edge(table, key, "AUTHORIZED_BY", evidence)
            self.edge(table, key, "OWNED_BY", evidence)
        elif isinstance(node, ast.ViewStmt):
            key = "db:view:" + qualified(node.view)
            self.emit("DATABASE_VIEW", key, qualified(node.view), self.scope, evidence, materialized=False)
            self.query(node.query, key, evidence)
        elif isinstance(node, ast.CreateTableAsStmt):
            materialized = node.objtype.name == "OBJECT_MATVIEW"
            key = ("db:view:" if materialized else "db:table:") + qualified(node.into.rel)
            self.emit("DATABASE_VIEW" if materialized else "TABLE", key, qualified(node.into.rel), self.scope, evidence, materialized=materialized)
            self.query(node.query, key, evidence)
        elif isinstance(node, ast.CreateFunctionStmt):
            self.function(node, evidence)
        elif isinstance(node, (ast.SelectStmt, ast.InsertStmt, ast.UpdateStmt, ast.DeleteStmt, ast.MergeStmt)):
            self.query(node, self.scope, evidence)
        elif isinstance(node, (ast.CreateEnumStmt, ast.CompositeTypeStmt, ast.CreateDomainStmt)):
            self.emit("DATA_RULE", self.unique(self.scope, "type"), type(node).__name__, self.scope, evidence,
                      ruleType="TYPE", expression=render(node), ownership="DATABASE_CONSTRAINT")
        elif isinstance(node, ast.TransactionStmt):
            pass
        else:
            self.emit("DATA_RULE", self.unique(self.scope, "statement"), type(node).__name__, self.scope, evidence,
                      ruleType="SQL_STATEMENT", expression=render(node)[:8000])
            self.unknown(f"{type(node).__name__} is parsed but its effects are not resolved", evidence)

    def sql(self, text, region=None):
        region = region or self.region
        try:
            statements = parse_sql(text)
        except parser.ParseError as error:
            self.failed = True
            self.run.unknown.add("MALFORMED_SQL")
            self.run.diagnostic("SQL_PARSE_ERROR", str(error), self.source.path, region.evidence()["startLine"], severity="ERROR")
            return False
        # pglast translates PostgreSQL byte offsets into Python character offsets.
        data = text
        previous = self.statement_region, self.ranges, self.bounds
        try:
            self.ranges = SqlRanges(region)
            for statement in statements:
                self.run.tick()
                begin = statement.stmt_location
                end = begin + statement.stmt_len if statement.stmt_len else len(data)
                while begin < end and data[begin:begin + 1].isspace():
                    begin += 1
                self.statement_region = region.slice(begin, end)
                self.bounds = (begin, end)
                self.statement(statement.stmt, region.evidence(begin, end))
        finally:
            self.statement_region, self.ranges, self.bounds = previous
        return True


def analyze(run):
    from liquibase import Changelogs
    sources = {source.path: source for source in run.sources({".sql", ".xml", ".yaml", ".yml", ".json"})}
    Changelogs(run, sources, Extractor).run_all()


def main():
    return cli(DESCRIPTOR, analyze)


if __name__ == "__main__":
    raise SystemExit(main())
