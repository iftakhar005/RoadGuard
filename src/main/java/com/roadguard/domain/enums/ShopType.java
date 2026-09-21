package com.roadguard.domain.enums;

public enum ShopType {

    GARAGE("Full garage"),
    TYRE_SHOP("Tyre shop"),
    BATTERY_SHOP("Battery shop"),
    MOBILE_MECHANIC("Mobile mechanic"),
    TOWING_SERVICE("Towing service"),
    FUEL_DELIVERY("Fuel delivery"),
    BODY_REPAIR("Body repair"),
    GENERAL_REPAIR("General repair");

    private final String label;

    ShopType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
