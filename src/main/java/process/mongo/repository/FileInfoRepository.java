package process.mongo.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import process.mongo.documents.FileInfo;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface FileInfoRepository extends MongoRepository<FileInfo, String> {

    @Query(value ="{jobId: ?0}", count=true)
    //SQL Equivalent : select count(*) from book where author=?
    public Integer getFileInfoCountByJobId(Long jobId);

}
