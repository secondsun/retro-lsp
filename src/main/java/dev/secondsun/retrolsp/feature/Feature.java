package dev.secondsun.retrolsp.feature;

import java.util.Optional;

import com.google.gson.JsonObject;

import dev.secondsun.retro.util.vo.TokenizedFile;


public interface Feature<PARAMS, RESULT> {

    /**
     * This method is called at the start of the language server once per feature. It provides the client with features supported by the server.
     * 
     * @param initializationData the initialization data object sent back to the language client
     */
    void initialize(JsonObject initializationData);

    /**
     * 
     * Calculate a result for the feature and return an optional
     * 
     * @param params parameters from client
     * @param fileContent line separated file content
     * @return RESULT or empty optional
     */
    Optional<RESULT> handle(PARAMS params, TokenizedFile fileContent);

}
