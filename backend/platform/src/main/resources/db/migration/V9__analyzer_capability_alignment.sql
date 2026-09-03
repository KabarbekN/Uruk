-- Match the protocol descriptors shipped with the 0.1.0 analyzer images.
UPDATE analyzer_definition SET
    supported_languages='["JAVA","TYPESCRIPT"]'::jsonb,
    capabilities='["SYMBOLS","IMPORTS","ANNOTATIONS","CONDITIONS","LOOPS","ASSIGNMENTS","LITERALS","CALLS"]'::jsonb
WHERE analyzer_key='tree-sitter' AND version='0.1.0';

UPDATE analyzer_definition SET
    supported_frameworks='["SPRING_BOOT","SPRING_MVC","SPRING_SECURITY","JPA"]'::jsonb,
    capabilities='["AUTHORIZATION","CALL_GRAPH","CONDITIONS","DATABASE_ACCESS","ENDPOINTS","SCHEDULED_JOBS","SIDE_EFFECTS","SYMBOLS","TEST_LINKS","TRANSACTIONS","TYPE_HIERARCHY","VALIDATIONS"]'::jsonb
WHERE analyzer_key='java-spring' AND version='0.1.0';

UPDATE analyzer_definition SET
    supported_frameworks='["POSTGRESQL","FLYWAY","LIQUIBASE"]'::jsonb,
    capabilities='["SCHEMAS","TABLES","COLUMNS","DEFAULTS","CONSTRAINTS","INDEXES","TRIGGERS","POLICIES","VIEWS","FUNCTIONS","DATABASE_ACCESS","DATA_OWNERSHIP"]'::jsonb
WHERE analyzer_key='postgresql' AND version='0.1.0';

UPDATE analyzer_definition SET
    supported_languages='["TYPESCRIPT"]'::jsonb,
    supported_frameworks='["NESTJS","CLASS_VALIDATOR"]'::jsonb,
    capabilities='["SYMBOLS","IMPORTS","ENDPOINTS","AUTHORIZATION","VALIDATIONS","CALL_GRAPH","CONDITIONS"]'::jsonb
WHERE analyzer_key='typescript-node' AND version='0.1.0';
