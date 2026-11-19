package tablut.engine;

import java.util.Arrays;

/**
 * Parameterized weights container for the Ashton Tablut heuristic evaluation engine.
 * 
 * Theoretical Rationale:
 * Tablut is a highly asymmetric game:
 * - White defenders (9 pieces: 1 King + 8 soldiers) prioritize corridor clearance,
 *   unblockable 2-way escape forks, diamond formation center control, king safety,
 *   and defender shield cohesion around the King.
 * - Black attackers (16 soldiers) prioritize perimeter containment, the 8-point
 *   rhombus blockade, camp front discipline ("keep the fronts"), king encirclement,
 *   camp-wall pinch traps, and empty Throne capture anvils.
 * 
 * Design Characteristics:
 * - Public primitive fields for zero-overhead direct access during search.
 * - Array serialization (toArray / fromArray) designed for automated parameter tuning (SPSA / GA).
 * - Backwards-compatible deserialization supporting 15, 16, 17, and 21 parameter vectors.
 */
public final class HeuristicWeights {

    public static final int NUM_WEIGHTS = 24;

    // --- White Evaluation Weights ---
    // Weight index 0: Material value per alive White soldier
    public int whitePiece = 316;

    // Weight index 1: Material value per captured Black attacker
    public int blackCaptured = 107;

    // Weight index 2: Distance penalty per step King is away from perimeter escapes
    public int kingDistance = 49;

    // Weight index 3: Bonus for 1 open orthogonal escape corridor to perimeter
    public int kingSingleEscape = 1342;

    // Weight index 4: Massive bonus for 2+ independent open escape corridors (unblockable 2-way fork)
    public int kingDoubleEscape = 18905;

    // Weight index 5: Bonus per White soldier occupying diamond/windmill squares {d3, f4, d6, f7}
    public int diamondOccupancy = 51;

    // Weight index 6: Bonus per legal square King can reach in one move
    public int kingMobility = 22;

    // Weight index 7: Penalty when King is vulnerable to immediate custodian capture
    public int kingVulnerabilityPenalty = 73;

    // --- Black Evaluation Weights ---
    // Weight index 8: Material value per alive Black soldier
    public int blackPiece = 98;

    // Weight index 9: Material value per captured White defender (attrition priority)
    public int whiteCaptured = 198;

    // Weight index 10: Bonus per Black soldier on the 8 rhombus squares {c2, g2, b3, h3, b7, h7, c8, g8}
    public int rhombusBlockade = 56;

    // Weight index 11: Bonus per camp doorway preserved {e2, e8, b5, h5} ("Keep the Fronts")
    public int campFrontPreserved = 70;

    // Weight index 12: Bonus per hostile piece directly adjacent to King on/near Throne
    public int kingSurroundedThrone = 47;

    // Weight index 13: Bonus for keeping King contained close to the central Throne
    public int kingContainment = 39;

    // Weight index 14: Mobility bonus for active Black soldiers
    public int blackMobility = 10;

    // Weight index 15: Bonus for connected blockade chains (orthogonal & diagonal soldier links)
    public int blackConnectedChains = 14;

    // Weight index 16: Bonus for swarming escaping King in open field (distFromCenter >= 2)
    public int kingOpenFieldContainment = 12;

    // --- Phase 12 Asymmetric Shield & Compound Spatial Features ---
    // Weight index 17: Bonus for White pawns forming protective shield within Chebyshev dist 1-2 of King
    public int kingDefenderShieldCohesion = 18;

    // Weight index 18: Non-linear quadratic penalty for White pawns dispersed > 3 Manhattan steps from King
    public int whitePawnDispersionPenalty = 5;

    // Weight index 19: Tactical bonus for Black threatening active camp-wall pinch traps against White defenders
    public int campPinchTraps = 39;

    // Weight index 20: Tactical bonus for active line-of-sight threats using empty Throne as capture anvil
    public int throneAnvilThreats = 29;

    // --- Phase 13 Proactive Escape & Tactical Refinements ---
    // Weight index 21: Proactive bonus for King threatening an unblockable 2-step escape fork (SAFE_FORKS)
    public int kingSafeForkThreat = 447;

    // --- Phase 14 Post-Autopsy Tactical Hardening (P1 & P4) ---
    // Weight index 22: Severe penalty when White King abandons Throne e5 without open escape corridors or safe forks
    public int kingThroneAbandonPenalty = 146;

    // Weight index 23: Penalty for Black abandoning the 8 outer camp corners when White threatens that sector
    public int campPerimeterIntegrityPenalty = 29;

    public HeuristicWeights() {
        // Defaults initialized above
    }

    /**
     * Copy constructor.
     */
    public HeuristicWeights(HeuristicWeights other) {
        copyFrom(other);
    }

    /**
     * Copies all weight values from another instance.
     */
    public void copyFrom(HeuristicWeights other) {
        this.whitePiece = other.whitePiece;
        this.blackCaptured = other.blackCaptured;
        this.kingDistance = other.kingDistance;
        this.kingSingleEscape = other.kingSingleEscape;
        this.kingDoubleEscape = other.kingDoubleEscape;
        this.diamondOccupancy = other.diamondOccupancy;
        this.kingMobility = other.kingMobility;
        this.kingVulnerabilityPenalty = other.kingVulnerabilityPenalty;

        this.blackPiece = other.blackPiece;
        this.whiteCaptured = other.whiteCaptured;
        this.rhombusBlockade = other.rhombusBlockade;
        this.campFrontPreserved = other.campFrontPreserved;
        this.kingSurroundedThrone = other.kingSurroundedThrone;
        this.kingContainment = other.kingContainment;
        this.blackMobility = other.blackMobility;
        this.blackConnectedChains = other.blackConnectedChains;
        this.kingOpenFieldContainment = other.kingOpenFieldContainment;

        this.kingDefenderShieldCohesion = other.kingDefenderShieldCohesion;
        this.whitePawnDispersionPenalty = other.whitePawnDispersionPenalty;
        this.campPinchTraps = other.campPinchTraps;
        this.throneAnvilThreats = other.throneAnvilThreats;
        this.kingSafeForkThreat = other.kingSafeForkThreat;
        this.kingThroneAbandonPenalty = other.kingThroneAbandonPenalty;
        this.campPerimeterIntegrityPenalty = other.campPerimeterIntegrityPenalty;
    }

    /**
     * Serializes weights into a primitive int array for automated tuning.
     */
    public int[] toArray() {
        return new int[] {
            whitePiece,
            blackCaptured,
            kingDistance,
            kingSingleEscape,
            kingDoubleEscape,
            diamondOccupancy,
            kingMobility,
            kingVulnerabilityPenalty,
            blackPiece,
            whiteCaptured,
            rhombusBlockade,
            campFrontPreserved,
            kingSurroundedThrone,
            kingContainment,
            blackMobility,
            blackConnectedChains,
            kingOpenFieldContainment,
            kingDefenderShieldCohesion,
            whitePawnDispersionPenalty,
            campPinchTraps,
            throneAnvilThreats,
            kingSafeForkThreat,
            kingThroneAbandonPenalty,
            campPerimeterIntegrityPenalty
        };
    }

    /**
     * Deserializes weights from a primitive int array.
     */
    public void fromArray(int[] arr) {
        if (arr == null || arr.length < 15) {
            throw new IllegalArgumentException("Array length must be at least 15");
        }
        this.whitePiece = arr[0];
        this.blackCaptured = arr[1];
        this.kingDistance = arr[2];
        this.kingSingleEscape = arr[3];
        this.kingDoubleEscape = arr[4];
        this.diamondOccupancy = arr[5];
        this.kingMobility = arr[6];
        this.kingVulnerabilityPenalty = arr[7];

        this.blackPiece = arr[8];
        this.whiteCaptured = arr[9];
        this.rhombusBlockade = arr[10];
        this.campFrontPreserved = arr[11];
        this.kingSurroundedThrone = arr[12];
        this.kingContainment = arr[13];
        this.blackMobility = arr[14];
        if (arr.length >= 16) {
            this.blackConnectedChains = arr[15];
        }
        if (arr.length >= 17) {
            this.kingOpenFieldContainment = arr[16];
        }
        if (arr.length >= 18) {
            this.kingDefenderShieldCohesion = arr[17];
        }
        if (arr.length >= 19) {
            this.whitePawnDispersionPenalty = arr[18];
        }
        if (arr.length >= 20) {
            this.campPinchTraps = arr[19];
        }
        if (arr.length >= 21) {
            this.throneAnvilThreats = arr[20];
        }
        if (arr.length >= 22) {
            this.kingSafeForkThreat = arr[21];
        }
        if (arr.length >= 23) {
            this.kingThroneAbandonPenalty = arr[22];
        }
        if (arr.length >= 24) {
            this.campPerimeterIntegrityPenalty = arr[23];
        }
    }

    /**
     * Resets all parameters to default literature-calibrated values.
     */
    public void resetToDefaults() {
        this.whitePiece = 316;
        this.blackPiece = 98;
        this.blackCaptured = 107;
        this.whiteCaptured = 198;
        this.kingDistance = 49;
        this.kingSingleEscape = 1342;
        this.kingDoubleEscape = 18905;
        this.diamondOccupancy = 51;
        this.kingMobility = 22;
        this.kingVulnerabilityPenalty = 73;

        this.rhombusBlockade = 56;
        this.campFrontPreserved = 70;
        this.kingSurroundedThrone = 47;
        this.kingContainment = 39;
        this.blackMobility = 10;
        this.blackConnectedChains = 14;
        this.kingOpenFieldContainment = 12;

        this.kingDefenderShieldCohesion = 18;
        this.whitePawnDispersionPenalty = 5;
        this.campPinchTraps = 39;
        this.throneAnvilThreats = 29;
        this.kingSafeForkThreat = 447;
        this.kingThroneAbandonPenalty = 146;
        this.campPerimeterIntegrityPenalty = 29;
    }
}
