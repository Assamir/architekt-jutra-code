package pl.devstyle.aj.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class LoggingJsonSchemaValidator implements JsonSchemaValidator {

    private final JsonSchemaValidator delegate;
    private final ObjectMapper objectMapper;

    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object instance) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Validating {} against schema {}",
                        instance != null ? instance.getClass().getSimpleName() : "null",
                        objectMapper.writeValueAsString(schema));
            }

            ValidationResponse response = delegate.validate(schema, instance);

            log.debug("Schema validation completed: valid={}", response.valid());

            return response;
        } catch (Exception ex) {
            log.error("Error during JSON schema validation", ex);
            throw new RuntimeException("Schema validation error", ex);
        }
    }
}
