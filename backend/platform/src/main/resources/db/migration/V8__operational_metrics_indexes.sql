CREATE INDEX analysis_task_finished ON analysis_task(finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX llm_execution_created ON llm_execution(created_at);
