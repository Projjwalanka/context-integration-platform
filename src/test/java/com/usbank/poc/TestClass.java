package com.usbank.poc;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

public class TestClass {	

	    public static void main(String... args) {

	        // URI examples: "neo4j://localhost", "neo4j+s://xxx.databases.neo4j.io"
	        final String dbUri = "neo4j+s://13097eaa.databases.neo4j.io";
	        final String dbUser = "13097eaa";
	        final String dbPassword = "boHPoP2ia26WXk5ZZO2e0HSnYOqgMrurJBuNNjGa6W4";

	        try (var driver = GraphDatabase.driver(dbUri, AuthTokens.basic(dbUser, dbPassword))) {
	            driver.verifyConnectivity();
	            System.out.println("Connection established.");
	        }
	    }
}
