package com.ataiva.serengeti.core.query;

/**
 * One token of query text. Names and keywords carry their lowercased text; strings and
 * numbers carry their value; symbols carry their text. {@code start} and {@code end} are
 * character offsets into the source.
 */
record Token(Kind kind, String text, Object value, int start, int end) {
    enum Kind { NAME, KEYWORD, STRING, NUMBER, SYMBOL, END }
}
