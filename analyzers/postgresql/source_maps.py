"""Source regions derived from parser marks, never reconstructed SQL locations."""

from dataclasses import dataclass
import bisect

from pglast import parser


@dataclass(frozen=True)
class Region:
    source: object
    text: str
    start: int
    end: int
    linear: bool = True

    @classmethod
    def whole(cls, source):
        return cls(source, source.text, 0, len(source.text))

    def evidence(self, start=0, end=None):
        end = len(self.text) if end is None else end
        if self.linear:
            return self.source.evidence(self.start + start, self.start + end)
        return self.source.evidence(self.start, self.end)

    def slice(self, start, end):
        if self.linear:
            return Region(self.source, self.text[start:end], self.start + start, self.start + end)
        return Region(self.source, self.text[start:end], self.start, self.end, False)

    def decoded(self, text):
        return Region(self.source, text, self.start, self.end, text == self.source.text[self.start:self.end])


def function_body(region, body):
    """Locate AS's string token through the PostgreSQL scanner, retaining escapes."""
    tokens = parser.scan(region.text)
    for index, token in enumerate(tokens[:-1]):
        if token.name != "AS" or tokens[index + 1].name != "SCONST":
            continue
        value = tokens[index + 1]
        raw = region.text[value.start:value.end + 1]
        if raw.startswith("$"):
            delimiter_end = raw.index("$", 1) + 1
            delimiter = raw[:delimiter_end]
            if raw.endswith(delimiter) and raw[delimiter_end:-delimiter_end] == body:
                return region.slice(value.start + delimiter_end, value.end + 1 - delimiter_end)
        # Escaped literals have no one-to-one offset mapping: the exact literal is evidence.
        return region.slice(value.start, value.end + 1).decoded(body)
    return region.decoded(body)


class BodyLocations:
    """Combine native PL/pgSQL line numbers with native scanner token boundaries."""

    def __init__(self, region):
        self.region = region
        self.tokens = parser.scan(region.text)
        self.lines = [0]
        for index, char in enumerate(region.text):
            if char == "\n":
                self.lines.append(index + 1)
        self.cursor = 0

    def line_region(self, line):
        if not isinstance(line, int) or line < 1 or line > len(self.lines):
            return self.region
        start = self.lines[line - 1]
        end = self.lines[line] if line < len(self.lines) else len(self.region.text)
        return self.region.slice(start, end)

    def statement(self, line, words=(), delimiter=";", advance=True):
        if not isinstance(line, int) or line < 1 or line > len(self.lines):
            return self.region
        candidates = []
        for index, token in enumerate(self.tokens):
            if token.start < self.cursor:
                continue
            row = bisect.bisect_right(self.lines, token.start)
            if row != line:
                continue
            word = self.region.text[token.start:token.end + 1].upper()
            previous = self.region.text[self.tokens[index - 1].start:self.tokens[index - 1].end + 1].upper() if index else ""
            if (not words or word in words) and previous != "END":
                candidates.append(index)
        if not candidates:
            return self.line_region(line)
        first = candidates[0]
        last = first
        nesting = 0
        cases = 0
        for index in range(first, len(self.tokens)):
            token = self.tokens[index]
            word = self.region.text[token.start:token.end + 1].upper()
            if word == "(":
                nesting += 1
            elif word == ")":
                nesting -= 1
            elif word == "CASE":
                cases += 1
            elif word == "END" and cases:
                cases -= 1
            last = index
            if word == delimiter and not nesting and not cases:
                break
        start, end = self.tokens[first].start, self.tokens[last].end + 1
        if advance:
            self.cursor = end
        return self.region.slice(start, end)
