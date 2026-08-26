package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.StorageConnectionDto;

public interface StorageConnectionService {

    ResponseDto addConnection(StorageConnectionDto dto) throws Exception;

    ResponseDto updateConnection(StorageConnectionDto dto) throws Exception;

    ResponseDto deleteConnection(Long storageConnectionId) throws Exception;

    ResponseDto fetchAllConnections() throws Exception;

    ResponseDto fetchConnectionById(Long storageConnectionId) throws Exception;

    ResponseDto testConnection(Long storageConnectionId) throws Exception;

    ResponseDto discoverBuckets(StorageConnectionDto dto) throws Exception;


    public ResponseDto cloneConnection(Long sourceId, StorageConnectionDto dto) throws Exception;
}
