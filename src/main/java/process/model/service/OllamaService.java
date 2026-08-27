package process.model.service;

/**
 * @author Nabeel Ahmed
 * */
public interface OllamaService {

    Object listModels() throws Exception;

    Object pullModel(String name) throws Exception;

    Object deleteModel(String name) throws Exception;

}
