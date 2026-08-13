package process.model.service;

public interface OllamaService {

    Object listModels() throws Exception;

    Object pullModel(String name) throws Exception;

    Object deleteModel(String name) throws Exception;

}
