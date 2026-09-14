package com.tsf.demo.provider;

import com.tencent.tsf.serviceregistry.TsfRegistration;
import com.tencent.tsf.serviceregistry.TsfRegistrationCustomizer;

public class TestTsfRegistrationCustomizer implements TsfRegistrationCustomizer {
    @Override
    public void customize(TsfRegistration tsfRegistration) {
        tsfRegistration.getService().setAddress(System.getProperty("tsf_address_ipv4","8.134.165.212"));
        tsfRegistration.getService().getMeta().put("TSF_ADDRESS_IPV4", System.getProperty("tsf_address_ipv4","8.134.165.212"));
    }
}
