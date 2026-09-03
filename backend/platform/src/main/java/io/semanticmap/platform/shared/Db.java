package io.semanticmap.platform.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Db {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Db(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(
                sql,
                (rs, n) -> {
                    var result = new LinkedHashMap<String, Object>();
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        String type = meta.getColumnTypeName(i);
                        if (value != null && (type.equals("json") || type.equals("jsonb"))) {
                            try {
                                value = mapper.readValue(value.toString(), Object.class);
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException("Invalid stored JSON", e);
                            }
                        } else if (value instanceof Timestamp t) {
                            value = t.toInstant().toString();
                        } else if (value instanceof OffsetDateTime t) {
                            value = t.toInstant().toString();
                        }
                        result.put(camel(meta.getColumnLabel(i)), value);
                    }
                    return result;
                },
                args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        return rows.getFirst();
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode JSON", e);
        }
    }

    public JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    private static String camel(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') upper = true;
            else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
