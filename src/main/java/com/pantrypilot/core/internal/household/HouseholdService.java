package com.pantrypilot.core.internal.household;

import com.pantrypilot.core.internal.CoreException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users, households, roles and invite codes. Every other part of the core
 * module asks {@link #requireMember} before it shows or changes a household.
 */
@Service
@Transactional
public class HouseholdService {

    /** No 0/O or 1/I/L, so a code read aloud or typed from a screenshot is unambiguous. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    public record UserView(long id, String displayName, String email) {
    }

    /** @param inviteCode only filled in for admins */
    public record HouseholdView(long id, String name, Role role, String inviteCode) {
    }

    public record MemberView(long userId, String displayName, Role role) {
    }

    private final AppUserRepository users;
    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final SecureRandom random = new SecureRandom();

    public HouseholdService(AppUserRepository users, HouseholdRepository households, MembershipRepository memberships) {
        this.users = users;
        this.households = households;
        this.memberships = memberships;
    }

    public UserView register(String displayName, String email) {
        String name = required(displayName, "A name", 100);
        String mail = required(email, "An email", 200).toLowerCase(Locale.ROOT);
        if (!mail.matches("[^@\\s]+@[^@\\s]+")) {
            throw CoreException.invalid("That is not an email address");
        }
        if (users.existsByEmail(mail)) {
            throw CoreException.conflict("That email is already registered");
        }
        return view(users.save(new AppUser(name, mail)));
    }

    /** The creator becomes the household's first admin. */
    public HouseholdView createHousehold(String name, long userId) {
        requireUser(userId);
        Household household = households.save(new Household(required(name, "A household name", 100), newInviteCode()));
        memberships.save(new Membership(household.getId(), userId, Role.ADMIN));
        return view(household, Role.ADMIN);
    }

    public HouseholdView join(String inviteCode, long userId) {
        requireUser(userId);
        String code = inviteCode == null ? "" : inviteCode.strip().toUpperCase(Locale.ROOT);
        Household household = households.findByInviteCode(code)
                .orElseThrow(() -> CoreException.notFound("No household has that invite code"));
        if (memberships.findByHouseholdIdAndUserId(household.getId(), userId).isPresent()) {
            throw CoreException.conflict("You are already a member of " + household.getName());
        }
        memberships.save(new Membership(household.getId(), userId, Role.MEMBER));
        return view(household, Role.MEMBER);
    }

    @Transactional(readOnly = true)
    public List<HouseholdView> householdsOf(long userId) {
        requireUser(userId);
        List<Membership> mine = memberships.findByUserIdOrderById(userId);
        Map<Long, Household> byId = households.findAllById(mine.stream().map(Membership::getHouseholdId).toList())
                .stream().collect(Collectors.toMap(Household::getId, Function.identity()));
        return mine.stream().map(m -> view(byId.get(m.getHouseholdId()), m.getRole())).toList();
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(long householdId, long actorId) {
        requireMember(householdId, actorId);
        List<Membership> all = memberships.findByHouseholdIdOrderById(householdId);
        Map<Long, AppUser> byId = users.findAllById(all.stream().map(Membership::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return all.stream()
                .map(m -> new MemberView(m.getUserId(), byId.get(m.getUserId()).getDisplayName(), m.getRole()))
                .toList();
    }

    public MemberView changeRole(long householdId, long actorId, long targetUserId, Role role) {
        requireAdmin(householdId, actorId);
        if (role == null) {
            throw CoreException.invalid("A role is required: ADMIN or MEMBER");
        }
        Membership target = memberships.findByHouseholdIdAndUserId(householdId, targetUserId)
                .orElseThrow(() -> CoreException.notFound("That person is not in this household"));
        if (target.getRole() == Role.ADMIN && role != Role.ADMIN) {
            requireAnotherAdmin(householdId);
        }
        target.setRole(role);
        return new MemberView(targetUserId, requireUser(targetUserId).getDisplayName(), role);
    }

    /** Admins can remove anyone; a member can remove only themselves (leave). */
    public void removeMember(long householdId, long actorId, long targetUserId) {
        Membership actor = requireMember(householdId, actorId);
        if (actorId != targetUserId && actor.getRole() != Role.ADMIN) {
            throw CoreException.forbidden("Only an admin can remove other members");
        }
        Membership target = memberships.findByHouseholdIdAndUserId(householdId, targetUserId)
                .orElseThrow(() -> CoreException.notFound("That person is not in this household"));
        if (target.getRole() == Role.ADMIN) {
            requireAnotherAdmin(householdId);
        }
        memberships.delete(target);
    }

    /** Makes the old code useless, for when it was shared too widely. */
    public HouseholdView regenerateInviteCode(long householdId, long actorId) {
        requireAdmin(householdId, actorId);
        Household household = households.findById(householdId).orElseThrow();
        household.setInviteCode(newInviteCode());
        return view(household, Role.ADMIN);
    }

    /** @throws CoreException FORBIDDEN when the user is not in the household */
    @Transactional(readOnly = true)
    public Membership requireMember(long householdId, long userId) {
        return memberships.findByHouseholdIdAndUserId(householdId, userId)
                .orElseThrow(() -> CoreException.forbidden("You are not a member of that household"));
    }

    @Transactional(readOnly = true)
    public Membership requireAdmin(long householdId, long userId) {
        Membership membership = requireMember(householdId, userId);
        if (membership.getRole() != Role.ADMIN) {
            throw CoreException.forbidden("Only a household admin can do that");
        }
        return membership;
    }

    @Transactional(readOnly = true)
    public Map<Long, String> displayNames(Iterable<Long> userIds) {
        return users.findAllById(userIds).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
    }

    @Transactional(readOnly = true)
    public long userCount() {
        return users.count();
    }

    private AppUser requireUser(long userId) {
        return users.findById(userId).orElseThrow(() -> CoreException.notFound("No such user"));
    }

    private void requireAnotherAdmin(long householdId) {
        if (memberships.countByHouseholdIdAndRole(householdId, Role.ADMIN) <= 1) {
            throw CoreException.conflict("A household needs at least one admin. Make someone else an admin first.");
        }
    }

    private String newInviteCode() {
        while (true) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            if (!households.existsByInviteCode(code.toString())) {
                return code.toString();
            }
        }
    }

    private static String required(String value, String what, int maxLength) {
        if (value == null || value.isBlank()) {
            throw CoreException.invalid(what + " is required");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw CoreException.invalid(what + " can be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static UserView view(AppUser user) {
        return new UserView(user.getId(), user.getDisplayName(), user.getEmail());
    }

    private static HouseholdView view(Household household, Role role) {
        return new HouseholdView(household.getId(), household.getName(), role,
                role == Role.ADMIN ? household.getInviteCode() : null);
    }
}
