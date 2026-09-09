UPDATE analyzer_definition
SET supported_languages = '["TYPESCRIPT","JAVASCRIPT"]'::jsonb,
    supported_frameworks = '["NESTJS","CLASS_VALIDATOR","REACT","REACT_NATIVE"]'::jsonb,
    capabilities = '["SYMBOLS","IMPORTS","ENDPOINTS","UI_ACTIONS","AUTHORIZATION","VALIDATIONS","CALL_GRAPH","CONDITIONS"]'::jsonb
WHERE analyzer_key = 'typescript-node' AND version = '0.1.0';
