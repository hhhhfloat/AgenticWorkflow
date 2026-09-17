package com.myagent.workflow.core.newcore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.workflow.core.AgentConfig;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewMain {

    /// Construction variables
    private final String apikey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private static Consumer<String> logConsumer = null;
    private static final Logger logger = LoggerFactory.getLogger(NewMain.class);


    /// Runtime variables
    private Thread runningThread = null;
    private String currentModel;


    public NewMain(AgentConfig runConfig){

        this.apikey = runConfig.apiKey();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        File sandbox = new File(AgentConfig.getSandboxDir());
        if(!sandbox.exists()){
            sandbox.mkdirs();
        }

        this.currentModel = runConfig.model();

    }

    public String runUnderstand(String userRequest, int maxIterations) throws IOException {
        /// Step 1 -- Understand Request
        try{
            /// Context manager part
            final String systemPrompt = NewSystemPrompt.get(WorkStyle.UNDERSTAND);

            List<Map<String, Object>> msg = new ArrayList<>();
            msg.add(Map.of("role", "system", "content", systemPrompt));
            msg.add(Map.of("role", "user", "content", userRequest));

            JsonNode root = sendAndReceive(msg);

            JsonNode choices = root.get("choices");
            if(choices == null || choices.isEmpty()){
                throw new IOException("API responded abnormally");
            }
            JsonNode messageNode = choices.get(0).get("message");

            if(messageNode.has("content") && !messageNode.get("content").isNull()){
                String content = messageNode.get("content").asText();
                if(!content.isEmpty()){
                    logIf("💬 " + content);
                    return content;
                }else{
                    return "Null response.";
                }
            }
            else{
                return "Null response.";
            }
        }catch(IOException e){
            throw e;
        } finally{

        }

    }

    public JsonNode sendAndReceive(List<Map<String, Object>> msg) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", currentModel);
        requestBody.put("messages", msg);
        /// requestBody.put("tools", null);
        /// requestBody.put("tool_choice", "auto");

        String jsonBody = null;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        Request httpRequest = new Request.Builder()
                .url(AgentConfig.getApiUrl())
                .header("Authorization", "Bearer " + apikey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        String responseBody;
        try(Response response = httpClient.newCall(httpRequest).execute()){
            if(!response.isSuccessful()){
                String errBody = response.body() != null? response.body().string() : "(Null response body)";
                throw new IOException("API request failed: "+ response.code() + " " + response.message() + " | " + errBody);
            }
            ResponseBody body = response.body();
            if(body == null){
                throw new IOException("Null Response body");
            }
            responseBody = body.string();

            JsonNode root = objectMapper.readTree(responseBody);
            return root;
        }
    }


    public static void logIf(String message){
        if(logConsumer != null){
            logConsumer.accept(message);
        } else{
            logger.info(message);
        }
    }



}
