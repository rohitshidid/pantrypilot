package com.pantrypilot.core.testsupport;

import com.pantrypilot.core.internal.household.HouseholdService;
import java.util.concurrent.atomic.AtomicLong;

/** A fresh household with its admin, so every test works on data nobody else touches. */
public record Kitchen(long householdId, long adminId, String inviteCode) {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    public static Kitchen create(HouseholdService households) {
        long adminId = newUser(households, "Admin");
        var household = households.createHousehold("Test kitchen", adminId);
        return new Kitchen(household.id(), adminId, household.inviteCode());
    }

    public static long newUser(HouseholdService households, String name) {
        return households.register(name, "user" + SEQUENCE.incrementAndGet() + "@example.com").id();
    }

    /** Registers a new user and adds them to this household as a plain member. */
    public long addMember(HouseholdService households, String name) {
        long userId = newUser(households, name);
        households.join(inviteCode, userId);
        return userId;
    }
}
