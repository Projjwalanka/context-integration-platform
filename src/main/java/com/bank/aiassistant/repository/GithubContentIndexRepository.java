package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.GithubContentIndex;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GithubContentIndexRepository extends MongoRepository<GithubContentIndex, String> {

    void deleteByConnectorId(String connectorId);

    List<GithubContentIndex> findByUserIdAndConnectorIdIn(String userId, List<String> connectorIds);

}
