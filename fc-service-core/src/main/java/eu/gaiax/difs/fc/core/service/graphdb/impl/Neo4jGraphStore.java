package eu.gaiax.difs.fc.core.service.graphdb.impl;

import eu.gaiax.difs.fc.core.config.GraphDbConfig;
import eu.gaiax.difs.fc.core.pojo.OpenCypherQuery;
import eu.gaiax.difs.fc.core.pojo.SdClaim;
import eu.gaiax.difs.fc.core.service.graphdb.GraphStore;
import eu.gaiax.difs.fc.core.service.graphdb.QueryGraph;
import lombok.extern.log4j.Log4j2;
import org.neo4j.driver.*;
import org.neo4j.driver.internal.InternalNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class Neo4jGraphStore implements AutoCloseable, GraphStore, QueryGraph {

    private final Driver driver;

    @Autowired
    public Neo4jGraphStore(GraphDbConfig config) throws Exception {
        this(config.getUri(), config.getUser(), config.getPassword());
    }

    public Neo4jGraphStore(String uri, String user, String password) throws Exception {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        log.info("Connected with Neo4j");
        initialiseGraph();
    }


    @Override
    public void close() throws Exception {
        driver.close();
    }

    private void initialiseGraph() throws Exception {
        if (databaseExists()) {
            log.info("Graph already loaded");
        } else {
            initGraph();
        }
    }

    private boolean databaseExists() throws Exception {
        try (Session session = driver.session()) {
            Result result = session.run("CALL gds.graph.exists('neo4j');");
            if (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Value value = record.get("exists");
                if (value == null) {
                    throw new Exception(
                            "Did not get the info whether or not the corresponding database in neo4j exists.");
                } else {
                    return value.asBoolean();
                }
            } else {
                throw new Exception("Did not get the info whether or not the corresponding database in neo4j exists.");
            }

        } catch (Exception e) {
            log.error("Tried to access neo4j querying for graph exists.");
            throw e;
        }
    }

    private void initGraph() {
        try (Session session = driver.session()) {
            session.run("CALL n10s.graphconfig.init();"); /// run only when creating a new graph
            session.run("CREATE CONSTRAINT n10s_unique_uri ON (r:Resource) ASSERT r.uri IS UNIQUE");
            log.info("n10 procedure and Constraints are loaded successfully");
        } catch (org.neo4j.driver.exceptions.ClientException e) {
            log.error("Could not initialize Neo4j Graph");
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addClaims(List<SdClaim> sdClaimList, String credentialSubject) {
        String payload = "";

        try (Session session = driver.session()) {
            for (SdClaim sdClaim : sdClaimList) {
                String subject=sdClaim.getSubject().substring(1,sdClaim.getSubject().length()-1);
                if (subject.equals(credentialSubject)){
                    payload = payload + sdClaim.getSubject() + " " + sdClaim.getPredicate() + " " + sdClaim.getObject()
                            + "	. \n";
                }
            }

            payload = payload +"<"+ credentialSubject+">"+" "+"<gx-participant:hasURI>"+" "+"\""+credentialSubject+"\"^^<http://www.w3.org/2001/XMLSchema#string>"+ "	. \n";

            String query = " WITH '\n" + payload + "' as payload\n"
                    + "CALL n10s.rdf.import.inline(payload,\"N-Triples\") YIELD terminationStatus, triplesLoaded\n"
                    + "RETURN terminationStatus, triplesLoaded";

            log.debug("Query; " + query);
            session.run(query);
        } catch (Exception e) {
            log.error("Could not update list of self description claims");
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteClaims(String credentialSubject) {
        log.debug("Beginning claims deletion");
        String query = "MATCH (n{uri: '"+credentialSubject+"'})\n" +
                "DELETE n";
        try (Session session = driver.session()) {
            session.run(query);
        }
        log.debug("Deleting executed successfully ");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map<String, String>> queryData(OpenCypherQuery sdQuery) {

        try (Session session = driver.session(); Transaction tx = session.beginTransaction()) {
            List<Map<String, String>> resultList = new ArrayList<>();
            log.debug("Beginning transaction");
            Result result = tx.run(sdQuery.getQuery());
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Map<String, Object> map = record.asMap();
                Map<String,String>  outputMap= new HashMap<String,String>();
                for (var entry : map.entrySet()) {
                    if( entry.getValue() instanceof String ) {
                        outputMap.put(entry.getKey(), entry.getValue().toString());
                    } else if (entry.getValue() == null) {
                        outputMap.put(entry.getKey(), null);
                    }
                    else if( entry.getValue() instanceof InternalNode){
                        InternalNode SDNode= (InternalNode) entry.getValue();
                        outputMap.put("n.uri", SDNode.get("uri").toString().replace("\"", ""));
                    }
                }
                resultList.add(outputMap);
            }
            log.debug("Query executed successfully ");
            return resultList;
        } catch (Exception e) {
            log.debug("Query unsuccessful " + e);
            throw e;
        }
    }

}
