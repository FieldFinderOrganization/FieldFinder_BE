package com.example.FieldFinder.mapper;
import java.util.Map;

public class BankBinMapper {

    private static final Map<String, String> BANK_BIN_MAP = Map.of(
            "BIDV", "970418"
    );

    public static String getBankBin(String bankName) {
        if (bankName == null) return null;
        return BANK_BIN_MAP.getOrDefault(bankName.trim(), null);
    }
}
