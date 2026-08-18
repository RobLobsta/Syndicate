/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.PartCategory;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The kinds of slot a part can offer and a part can occupy
 * (docs/05_vehicle_part_system.md#D05-S4.3).
 *
 * <p>Each constant carries the categories it accepts, so assembly validation (D05-S5.1) reads the
 * rule off the enum instead of keeping a parallel table that can drift from this one.
 */
public enum SlotType {
    ROOT(EnumSet.of(PartCategory.CHASSIS)),
    WHEEL(EnumSet.of(PartCategory.WHEEL)),
    /** A rotor mast or tail-rotor gearbox: where a lifting rotor bolts on (D05-S4.3). */
    ROTOR_MOUNT(EnumSet.of(PartCategory.ROTOR)),
    HARDPOINT(EnumSet.of(PartCategory.WEAPON, PartCategory.UTILITY)),
    /** A bodywork mount: a door aperture, a bonnet hinge line, a bumper mount. */
    PANEL(EnumSet.of(PartCategory.PANEL)),
    TURRET_MOUNT(EnumSet.of(PartCategory.WEAPON)),
    ACCESSORY(EnumSet.of(PartCategory.DECORATIVE)),
    SUBSLOT(EnumSet.of(PartCategory.WEAPON, PartCategory.UTILITY, PartCategory.DECORATIVE));

    private final Set<PartCategory> accepts;

    SlotType(Set<PartCategory> accepts) {
        this.accepts = Collections.unmodifiableSet(accepts);
    }

    /** The part categories this slot type will hold (D05-S4.3). */
    public Set<PartCategory> accepts() {
        return accepts;
    }

    /** True when a part of this category may occupy this slot type. */
    public boolean acceptsCategory(PartCategory category) {
        return accepts.contains(category);
    }
}
