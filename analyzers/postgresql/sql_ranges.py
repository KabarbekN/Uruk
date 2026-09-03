"""SQL clause spans from the native scanner; nested queries fall back to their statement."""

from pglast import parser


class SqlRanges:
    def __init__(self, region):
        self.region = region
        self.tokens = parser.scan(region.text)

    def clause(self, field, bounds, fallback):
        def evidence(start, end):
            while end > start and self.region.text[end - 1].isspace():
                end -= 1
            return self.region.evidence(start, end)

        starts = {"whereClause": {"WHERE"}, "havingClause": {"HAVING"}, "sortClause": {"ORDER"},
                  "groupClause": {"GROUP"}, "targetList": {"SELECT", "SET"}, "fromClause": {"FROM"}}
        boundaries = {"FROM", "WHERE", "HAVING", "GROUP", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR", "WINDOW", "RETURNING", "UNION", "INTERSECT", "EXCEPT", ";"}
        depth, begin = 0, None
        for token in self.tokens:
            if not bounds[0] <= token.start < bounds[1]:
                continue
            word = self.region.text[token.start:token.end + 1].upper()
            if not depth:
                if begin is not None and word in boundaries:
                    return evidence(begin, token.start)
                if word in starts.get(field, set()):
                    begin = token.start
            if word == "(":
                depth += 1
            elif word == ")":
                depth -= 1
        return evidence(begin, bounds[1]) if begin is not None else fallback

    def function(self, node, fallback):
        location = getattr(node, "location", -1)
        if location is None or location < 0 or node.agg_filter or node.over:
            return fallback
        start = next((i for i, token in enumerate(self.tokens) if token.start == location), None)
        if start is None:
            return fallback
        depth, opened = 0, False
        for token in self.tokens[start:]:
            word = self.region.text[token.start:token.end + 1]
            if word == "(":
                depth += 1
                opened = True
            elif word == ")":
                depth -= 1
                if opened and depth == 0:
                    return self.region.evidence(location, token.end + 1)
        return fallback
