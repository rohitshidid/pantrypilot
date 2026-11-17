package com.pantrypilot.core.internal.web;

import com.pantrypilot.core.internal.household.HouseholdService;
import com.pantrypilot.core.internal.household.HouseholdService.HouseholdView;
import com.pantrypilot.core.internal.household.HouseholdService.MemberView;
import com.pantrypilot.core.internal.household.HouseholdService.UserView;
import com.pantrypilot.core.internal.household.Role;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users, households and members. Until login exists, {@code userId} is a plain
 * request parameter saying who is asking.
 */
@RestController
@RequestMapping("/api/core")
public class HouseholdController {

    record NewUser(String displayName, String email) {
    }

    record NewHousehold(String name) {
    }

    record Join(String inviteCode) {
    }

    record RoleChange(Role role) {
    }

    private final HouseholdService households;

    public HouseholdController(HouseholdService households) {
        this.households = households;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserView register(@RequestBody NewUser body) {
        return households.register(body.displayName(), body.email());
    }

    @GetMapping("/users/{userId}/households")
    List<HouseholdView> householdsOf(@PathVariable long userId) {
        return households.householdsOf(userId);
    }

    @PostMapping("/households")
    @ResponseStatus(HttpStatus.CREATED)
    HouseholdView create(@RequestParam long userId, @RequestBody NewHousehold body) {
        return households.createHousehold(body.name(), userId);
    }

    @PostMapping("/households/join")
    HouseholdView join(@RequestParam long userId, @RequestBody Join body) {
        return households.join(body.inviteCode(), userId);
    }

    @GetMapping("/households/{householdId}/members")
    List<MemberView> members(@PathVariable long householdId, @RequestParam long userId) {
        return households.members(householdId, userId);
    }

    @PutMapping("/households/{householdId}/members/{memberId}")
    MemberView changeRole(@PathVariable long householdId, @PathVariable long memberId, @RequestParam long userId,
            @RequestBody RoleChange body) {
        return households.changeRole(householdId, userId, memberId, body.role());
    }

    @DeleteMapping("/households/{householdId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long householdId, @PathVariable long memberId, @RequestParam long userId) {
        households.removeMember(householdId, userId, memberId);
    }

    @PostMapping("/households/{householdId}/invite-code")
    HouseholdView newInviteCode(@PathVariable long householdId, @RequestParam long userId) {
        return households.regenerateInviteCode(householdId, userId);
    }
}
