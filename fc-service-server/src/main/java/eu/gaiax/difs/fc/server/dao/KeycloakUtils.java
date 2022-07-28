package eu.gaiax.difs.fc.server.dao;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

public class KeycloakUtils {
    
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<Map<String, Object>>() {
    };
    
    public static String getErrorMessage(Response response) {
        String message;
        try {
            InputStream is = (InputStream) response.getEntity();
            message = CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));
            Map<String, Object> error = jsonMapper.readValue(message, MAP_TYPE_REF);
            message = (String) error.get("errorMessage");
        } catch (IOException ex) {
            message = ex.getMessage();
        }
        return message;
    }    

}
