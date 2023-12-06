package process.mongo.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import process.mongo.documents.Scraper;

@Repository
public interface ScraperRepository extends MongoRepository<Scraper, String> {
}
