-- The AI_PROVIDER lookup (V9) fed the AI Agents dialog's provider list. Providers are a fixed
-- set the console carries (OpenAI, Anthropic, Ollama, Azure OpenAI, OpenAI-compatible), and a
-- model connection names one; nothing reads the lookup any more, so its rows go.
DELETE FROM lookup_data WHERE parent_lookup_id IN (SELECT lookup_id FROM lookup_data WHERE lookup_type = 'AI_PROVIDER');
DELETE FROM lookup_data WHERE lookup_type = 'AI_PROVIDER';
