package com.pantrypilot.core.internal;

import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.household.HouseholdService.UserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * On a database with no users, creates "Demo User" (id 1) and "Demo Household"
 * (id 1), so the test page's default household and user work straight away.
 * Switch it off with {@code pantrypilot.core.seed-demo=false}.
 */
@Component
@ConditionalOnProperty(name = "pantrypilot.core.seed-demo", havingValue = "true", matchIfMissing = true)
class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final HouseholdService households;

    DemoData(HouseholdService households) {
        this.households = households;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (households.userCount() > 0) {
            return;
        }
        UserView user = households.register("Demo User", "demo@pantrypilot.local");
        var household = households.createHousehold("Demo Household", user.id());
        log.info("Created demo user {} and demo household {} (invite code {})",
                user.id(), household.id(), household.inviteCode());
    }
}
