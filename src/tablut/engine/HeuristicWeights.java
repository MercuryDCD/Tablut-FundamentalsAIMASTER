package tablut.engine;

import java.util.Arrays;

/**
 * Parameterized weights container for the Ashton Tablut heuristic evaluation engine.
 * 
 * Theoretical Rationale:
 * Tablut is a highly asymmetric game:
 * - White defenders (9 pieces: 1 King + 8 soldiers) prioritize corridor clearance,
 *   unblockable 2-way escape forks, diamond formation center control, and king safety.
 * - Black attackers (16 soldiers) prioritize perimeter containment, the 8-point
 *   rhombus blockade, camp front discipline ("keep the fronts"), and king encirclement.
 * 
 * Design Characteristics:
 * - Public primitive fields for zero-overhead direct access during search.
 * - Array serialization (toArray / fromArray) designed for Phase 5 automated
 *   parameter tuning (SPSA / Genetic Algorithms on remote compute servers).
 * - Calibrated default weights based on tournament literature (brAInmates, THoR, Hnefatafl Forum).
 */
public final class HeuristicWeights {

    public static final int NUM_WEIGHTS = 15;

    // --- White Evaluation Weights ---
    // Weight index 0: Material value per alive White soldier
    public int whitePiece = 140;

    // Weight index 1: Material value per captured Black attacker
    public int blackCaptured = 80;

    // Weight index 2: Distance penalty per step King is away from perimeter escapes
    public int kingDistance = 30;

    // Weight index 3: Bonus for 1 open orthogonal escape corridor to perimeter
    public int kingSingleEscape = 1500;

    // Weight index 4: Massive bonus for 2+ independent open escape corridors (unblockable 2-way fork)
    public int kingDoubleEscape = 18000;

    // Weight index 5: Bonus per White soldier occupying diamond/windmill squares {d3, f4, d6, f7}
    public int diamondOccupancy = 70;

    // Weight index 6: Bonus per legal square King can reach in one move
    public int kingMobility = 20;

    // Weight index 7: Penalty when King is vulnerable to immediate custodian capture
    public int kingVulnerabilityPenalty = 220;

    // --- Black Evaluation Weights ---
    // Weight index 8: Material value per alive Black soldier
    public int blackPiece = 80;

    // Weight index 9: Material value per captured White defender (attrition priority)
    public int whiteCaptured = 200;

    // Weight index 10: Bonus per Black soldier on the 8 rhombus squares {c2, g2, b3, h3, b7, h7, c8, g8}
    public int rhombusBlockade = 100;

    // Weight index 11: Bonus per camp doorway preserved {e2, e8, b5, h5} ("Keep the Fronts")
    public int campFrontPreserved = 60;

    // Weight index 12: Bonus per hostile piece directly adjacent to King on/near Throne
    public int kingSurroundedThrone = 280;

    // Weight index 13: Bonus for keeping King contained close to the central Throne
    public int kingContainment = 35;

    // Weight index 14: Mobility bonus for active Black soldiers
    public int blackMobility = 5;

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
            blackMobility
        };
    }

    /**
     * Deserializes weights from a primitive int array.
     */
    public void fromArray(int[] arr) {
        if (arr == null || arr.length < NUM_WEIGHTS) {
            throw new IllegalArgumentException("Array length must be at least " + NUM_WEIGHTS);
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
    }

    /**
     * Resets all parameters to default literature-calibrated values.
     */
    public void resetToDefaults() {
        this.whitePiece = 140;
        this.blackCaptured = 80;
        this.kingDistance = 30;
        this.kingSingleEscape = 1500;
        this.kingDoubleEscape = 18000;
        this.diamondOccupancy = 70;
        this.kingMobility = 20;
        this.kingVulnerabilityPenalty = 220;

        this.blackPiece = 80;
        this.whiteCaptured = 200;
        this.rhombusBlockade = 100;
        this.campFrontPreserved = 60;
        this.kingSurroundedThrone = 280;
        this.kingContainment = 35;
        this.blackMobility = 5;
    }
}
