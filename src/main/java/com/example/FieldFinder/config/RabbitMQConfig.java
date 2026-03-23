package com.example.FieldFinder.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Định nghĩa tên Hàng đợi (Queue), Trạm trung chuyển (Exchange) và Khóa định tuyến (Routing Key)

    public static final String EMAIL_EXCHANGE = "email_exchange";
    public static final String BOOKING_EMAIL_QUEUE = "email_queue";
    public static final String BOOKING_EMAIL_ROUTING_KEY = "email_routing_key";

    public static final String ORDER_EMAIL_QUEUE = "order_email_queue";
    public static final String ORDER_EMAIL_ROUTING_KEY = "order_email_routing_key";

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(EMAIL_EXCHANGE);
    }

    @Bean
    public Queue emailQueue() {
        return new Queue(BOOKING_EMAIL_QUEUE, true);
    }

    @Bean
    public Binding emailBinding(Queue emailQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(emailQueue).to(emailExchange).with(BOOKING_EMAIL_ROUTING_KEY);
    }

    @Bean
    public Queue orderEmailQueue() {
        return new Queue(ORDER_EMAIL_QUEUE, true);
    }

    @Bean
    public Binding orderEmailBinding(Queue orderEmailQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(orderEmailQueue).to(emailExchange).with(ORDER_EMAIL_ROUTING_KEY);
    }

}