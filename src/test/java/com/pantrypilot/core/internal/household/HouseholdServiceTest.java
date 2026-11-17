package com.pantrypilot.core.internal.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pantrypilot.core.internal.CoreException;
import com.pantrypilot.core.internal.household.HouseholdService.HouseholdView;
import com.pantrypilot.core.internal.household.HouseholdService.MemberView;
import com.pantrypilot.core.testsupport.Kitchen;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Households, roles and invite codes. */
@SpringBootTest
class HouseholdServiceTest {

    @Autowired
    private HouseholdService households;

    private static CoreException.Kind kindOf(Throwable t) {
        return ((CoreException) t).kind();
    }

    @Test
    void theCreatorIsAdminAndOthersJoinWithTheInviteCodeAsMembers() {
        Kitchen kitchen = Kitchen.create(households);
        long guest = kitchen.addMember(households, "Guest");

        assertThat(households.members(kitchen.householdId(), guest))
                .extracting(MemberView::userId, MemberView::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(kitchen.adminId(), Role.ADMIN),
                        org.assertj.core.groups.Tuple.tuple(guest, Role.MEMBER));
        // only admins are shown the invite code
        assertThat(households.householdsOf(guest)).singleElement()
                .satisfies(h -> assertThat(h.inviteCode()).isNull());
        assertThat(households.householdsOf(kitchen.adminId())).singleElement()
                .satisfies(h -> assertThat(h.inviteCode()).isEqualTo(kitchen.inviteCode()));
    }

    @Test
    void oneUserCanBelongToSeveralHouseholdsWithDifferentRoles() {
        Kitchen home = Kitchen.create(households);
        Kitchen parents = Kitchen.create(households);
        households.join(parents.inviteCode().toLowerCase(), home.adminId());   // codes are not case sensitive

        assertThat(households.householdsOf(home.adminId()))
                .extracting(HouseholdView::id, HouseholdView::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(home.householdId(), Role.ADMIN),
                        org.assertj.core.groups.Tuple.tuple(parents.householdId(), Role.MEMBER));
        assertThatThrownBy(() -> households.join(parents.inviteCode(), home.adminId()))
                .isInstanceOf(CoreException.class).hasMessageContaining("already a member");
    }

    @Test
    void outsidersSeeNothingAndAWrongCodeJoinsNothing() {
        Kitchen kitchen = Kitchen.create(households);
        long stranger = Kitchen.newUser(households, "Stranger");

        assertThatThrownBy(() -> households.members(kitchen.householdId(), stranger))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> households.join("NOPE1234", stranger))
                .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.kind()).isEqualTo(CoreException.Kind.NOT_FOUND));
    }

    @Test
    void onlyAdminsChangeRolesAndTheLastAdminCannotStepDown() {
        Kitchen kitchen = Kitchen.create(households);
        long guest = kitchen.addMember(households, "Guest");
        long h = kitchen.householdId();

        assertThatThrownBy(() -> households.changeRole(h, guest, guest, Role.ADMIN))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> households.changeRole(h, kitchen.adminId(), kitchen.adminId(), Role.MEMBER))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.CONFLICT));
        assertThatThrownBy(() -> households.removeMember(h, kitchen.adminId(), kitchen.adminId()))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.CONFLICT));

        households.changeRole(h, kitchen.adminId(), guest, Role.ADMIN);
        households.changeRole(h, guest, kitchen.adminId(), Role.MEMBER);   // now there is another admin
        assertThat(households.requireMember(h, kitchen.adminId()).getRole()).isEqualTo(Role.MEMBER);
    }

    @Test
    void membersCanLeaveButOnlyAdminsRemoveOthers() {
        Kitchen kitchen = Kitchen.create(households);
        long anna = kitchen.addMember(households, "Anna");
        long ben = kitchen.addMember(households, "Ben");
        long h = kitchen.householdId();

        assertThatThrownBy(() -> households.removeMember(h, anna, ben))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.FORBIDDEN));
        households.removeMember(h, anna, anna);
        households.removeMember(h, kitchen.adminId(), ben);

        assertThat(households.members(h, kitchen.adminId())).hasSize(1);
        assertThat(households.householdsOf(anna)).isEmpty();
    }

    @Test
    void aNewInviteCodeMakesTheOldOneUseless() {
        Kitchen kitchen = Kitchen.create(households);
        long guest = kitchen.addMember(households, "Guest");
        long late = Kitchen.newUser(households, "Late");

        assertThatThrownBy(() -> households.regenerateInviteCode(kitchen.householdId(), guest))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.FORBIDDEN));
        String fresh = households.regenerateInviteCode(kitchen.householdId(), kitchen.adminId()).inviteCode();

        assertThat(fresh).isNotEqualTo(kitchen.inviteCode()).hasSize(8);
        assertThatThrownBy(() -> households.join(kitchen.inviteCode(), late))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.NOT_FOUND));
        assertThat(households.join(fresh, late).role()).isEqualTo(Role.MEMBER);
    }

    @Test
    void registrationNeedsARealLookingUniqueEmail() {
        assertThatThrownBy(() -> households.register("X", "not-an-email")).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> households.register(" ", "a@b.c")).isInstanceOf(CoreException.class);
        var user = households.register("Dup", "Dup" + System.nanoTime() + "@Example.com");
        assertThatThrownBy(() -> households.register("Dup again", user.email().toUpperCase()))
                .satisfies(t -> assertThat(kindOf(t)).isEqualTo(CoreException.Kind.CONFLICT));
    }
}
