package com.example.FieldFinder.service.strategy.payment;

import com.example.FieldFinder.Enum.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentStrategyFactory {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    public PaymentStrategyFactory(List<PaymentStrategy> strategyList) {
        this.strategies = new EnumMap<>(PaymentMethod.class);
        for (PaymentStrategy s : strategyList) {
            this.strategies.put(s.getMethod(), s);
        }
    }

    public PaymentStrategy get(PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw new RuntimeException("No payment strategy registered for: " + method);
        }
        return strategy;
    }
}
