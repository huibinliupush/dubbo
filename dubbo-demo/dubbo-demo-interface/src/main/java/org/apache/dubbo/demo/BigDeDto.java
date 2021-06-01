package org.apache.dubbo.demo;

import java.io.Serializable;
import java.math.BigDecimal;

public class BigDeDto implements Serializable {


    private static final long serialVersionUID = 8667727411139896001L;

    private BigDecimal decimal;

    private String name;

    public BigDecimal getDecimal() {
        return decimal;
    }

    public void setDecimal(BigDecimal decimal) {
        this.decimal = decimal;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
