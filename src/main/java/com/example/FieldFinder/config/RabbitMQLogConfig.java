package com.example.FieldFinder.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQLogConfig {

    public static final String QUEUE_LOG = "user_interaction_log_queue";
    public static final String EXCHANGE_LOG = "logging_exchange";
    public static final String ROUTING_KEY_LOG = "log.routing.key";

    @Bean
    public Queue logQueue() {
        return new Queue(QUEUE_LOG, true);
    }

    @Bean
    public DirectExchange logExchange() {
        return new DirectExchange(EXCHANGE_LOG);
    }

    @Bean
    public Binding logBinding(Queue logQueue, DirectExchange logExchange) {
        return BindingBuilder.bind(logQueue).to(logExchange).with(ROUTING_KEY_LOG);
    }

    // Convert Java Object sang JSON khi đẩy vào RabbitMQ
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}