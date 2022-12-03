package com.github.sseserver;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public class LocalConnectionControllerTest {
    public static void main(String[] args) throws IOException {
        InetSocketAddress inetSocketAddress = new InetSocketAddress(0);

        ObjectMapper objectMapper = new ObjectMapper();
//        JsonMapper.builder()
//                        .nodeFactory(new JsonNodeFactory())
//        objectMapper.addHandler(new DeserializationProblemHandler() {
//        })
//        objectMapper.registerModule(new SimpleModule() {
//            @Override
//            public void setupModule(SetupContext context) {
//                super.setupModule(context);
//                context.addBeanSerializerModifier(new BeanSerializerModifier() {
//                    @Override
//                    public JsonSerializer<?> modifySerializer(
//                            SerializationConfig config, BeanDescription desc, JsonSerializer<?> serializer) {
//                        config.withoutAttribute()
//                        desc.findProperties().add()
//                        return serializer;
//                    }
//                });
//            }
//        });

//        objectMapper.setSerializerFactory(objectMapper.getSerializerFactory()
//                .withSerializerModifier(new BeanSerializerModifier(){
//                    @Override
//                    public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
//                        beanProperties.add(new BeanPropertyWriter(){
//
//                        });
//                        return super.changeProperties(config, beanDesc, beanProperties);
//                    }
//                }));
        SseServerApplicationTests.MyAccessUser user = new SseServerApplicationTests.MyAccessUser();
        user.setId(1);

        JsonNode jsonNode = objectMapper.valueToTree(user);
        if(jsonNode instanceof ObjectNode){
            ((ObjectNode) jsonNode).put("@type",user.getClass().getName());
        }

        String s = objectMapper.writeValueAsString(user);
        System.out.println("s = " + s);
    }
}
