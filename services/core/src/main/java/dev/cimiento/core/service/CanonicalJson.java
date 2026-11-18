package dev.cimiento.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Stable JSON (sorted keys) and SHA-256 helpers, used for hashing audit entries and requests. */
@Component
public class CanonicalJson {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        try {
            if (value instanceof JsonNode node) {
                // re-read into a tree with sorted keys
                Object plain = mapper.treeToValue(node, Object.class);
                return mapper.writeValueAsString(plain);
            }
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise to JSON", e);
        }
    }

    public JsonNode read(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON is invalid", e);
        }
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
