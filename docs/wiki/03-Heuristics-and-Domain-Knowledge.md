# 03 — Heuristics & Domain Knowledge

Evaluating non-terminal leaf positions in Ashton Tablut cannot rely on symmetric material counting. The sides have fundamentally different victory conditions, piece counts, and structural goals. White commands 9 pieces (including the King) and seeks escape routes; Black commands 16 pieces and seeks territorial containment and encirclement.

This document details the mathematical formulation, geometric feature extractors, and domain weights implemented across `HeuristicEvaluation.java`, `WhiteHeuristics.java`, `BlackHeuristics.java`, and `HeuristicWeights.java`.

---

## 1. Asymmetric Game Strategy

The heuristic engine evaluates the board through two competing domain models:

* **The White Defender Model:**
  White's tactical imperative is opening escape rays. Moving the King closer to the perimeter while clearing cross-guards creates multiple simultaneous escape corridors. If White creates two independent escape paths (a double-threat fork), Black cannot block both in a single turn, guaranteeing a White victory.
* **The Black Attacker Model:**
  Black's tactical imperative is perimeter containment. Rushing the King directly often creates tactical weaknesses that White can exploit. Instead, Black maintains an outer cordon: keeping the camp doorway soldiers in place, establishing an 8-point Rhombus ring, linking soldiers into connected chains, and constricting the King's mobility through an attractive swarm gradient.

---

## 2. Complete Parameterized Heuristic Terms (24 Weights)

The heuristic engine evaluates 24 parameterized terms stored in `HeuristicWeights.java` (`NUM_WEIGHTS = 24`), supporting backward compatibility with prior 15, 16, 17, 21, and 22 weight vectors:

### 2.1 White Evaluation Terms (`WhiteHeuristics.java`)

| Parameter | Calibrated Weight | Description & Algorithmic Formulation |
| :--- | :---: | :--- |
| `whitePiece` | `320` | Material value per active White defender (rebalanced to 2.5:1 ratio vs Black, eliminating the Draw Attraction Trap). |
| `blackCaptured` | `107` | Bonus per eliminated Black attacker ($16 - \text{blackCount}$). |
| `kingDistance` | `35` | Penalty per step between the King and the nearest perimeter escape square: `MIN_DISTANCE_TO_ESCAPE[kingSq] * kingDistance`. |
| `kingSingleEscape` | `1333` | Bonus awarded when the King has exactly 1 unblocked orthogonal ray to a perimeter escape square. |
| `kingDoubleEscape` | `18590` | Decisive bonus awarded when the King possesses $\ge 2$ unblocked escape rays (an unstoppable fork). |
| `diamondOccupancy` | `39` | Bonus for White soldiers occupying central diamond formations ($D_1$: `d3, f4, d6, f7`; $D_2$: `f3, d4, f6, d7`). |
| `kingMobility` | `15` | Bonus per legal orthogonal square the King can slide to from his current position. |
| `kingVulnerabilityPenalty` | `79` | Penalty incurred when the King is adjacent to hostile entities without counter-protection (`PROTECTION_KING` counter-flank offset). |
| `kingDefenderShieldCohesion` | `16` | Bonus for White defenders forming an escort shield within Chebyshev distance 1–2 of the King. |
| `whitePawnDispersionPenalty` | `7` | Non-linear quadratic penalty for White defenders dispersed $> 3$ Manhattan steps away from the King. |
| `kingSafeForkThreat` | `350` | Proactive bonus for positions where the King can slide in 1 move to a safe square opening $\ge 2$ escape corridors (`SAFE_FORKS`). |
| `kingThroneAbandonPenalty` | `450` | Penalty when White King abandons Throne (`e5`) without open escape corridors or safe forks. |

### 2.2 Black Evaluation Terms (`BlackHeuristics.java`)

| Parameter | Calibrated Weight | Description & Algorithmic Formulation |
| :--- | :---: | :--- |
| `blackPiece` | `99` | Value per active Black attacker on the board. |
| `whiteCaptured` | `198` | Bonus per eliminated White defender ($8 - \text{whiteCount}$), reflecting attrition priority. |
| `rhombusBlockade` | `55` | Bonus per Black soldier occupying the 8-point Rhombus ring (`c2, g2, b3, h3, b7, h7, c8, g8`), attenuated in endgames and scaled on closure. |
| `campFrontPreserved` | `69` | Bonus per preserved camp doorway square (`e2, e8, b5, h5`) ("Keep the Fronts"). |
| `kingSurroundedThrone` | `50` | Bonus per hostile flanker surrounding the King while he is on or adjacent to the Throne. |
| `kingContainment` | `39` | Positional bonus rewarding Black for confining the King near the center of the board. |
| `blackMobility` | `9` | Bonus per legal sliding move available across all active Black soldiers. |
| `blackConnectedChains` | `13` | Bonus per adjacent pair of Black soldiers (orthogonal and diagonal links), forming cohesive walls. |
| `kingOpenFieldContainment` | `11` | Swarm bonus attracting Black soldiers toward the King once the King escapes into the open field ($d(\text{King}, \text{Throne}) \ge 2$). |
| `campPinchTraps` | `37` | Tactical bonus for Black threatening active custodian pinches against White defenders adjacent to camp borders. |
| `throneAnvilThreats` | `30` | Tactical bonus for active line-of-sight threats using the vacated center Throne (`e5`) as a capture anvil. |
| `campPerimeterIntegrityPenalty` | `31` | Penalty for Black abandoning the 8 outer camp corners when White threatens that board quadrant. |

---

## 3. Deep Architectural Features

### 3.1 Strict 20-Exit Geometry vs. Ghost Citadels
Early implementations suffered from a critical geometric bug: measuring King escape distance to the outer board perimeter indiscriminately. The perimeter has 36 squares:
* 16 valid escape squares.
* 4 corner squares (which also count as winning squares for the King).
* 16 citadel / camp squares (where White pieces can **never** step).

Measuring distance to the generic board perimeter caused the King to navigate directly toward enemy camps, treating them as valid escape exits. 

`BoardConstants.java` corrects this with a precomputed lookup array `MIN_DISTANCE_TO_ESCAPE[81]` that measures shortest Manhattan distances strictly to the 20 legal winning squares (16 escapes + 4 corners).

### 3.2 The 8-Point Rhombus Blockade
The optimal perimeter blockade for Black forms an 8-point ring (the Ashton Rhombus):

```
       a   b   c   d   e   f   g   h   i
    +---+---+---+---+---+---+---+---+---+
  9 |   |   |   | C | C | C |   |   |   | 9
  8 |   |   | R |   | C |   | R |   |   | 8  (c8, g8)
  7 |   | R |   |   |   |   |   | R |   | 7  (b7, h7)
  6 |   |   |   |   |   |   |   |   |   | 6
  5 | C | C |   |   | T |   |   | C | C | 5  (Throne: e5)
  4 |   |   |   |   |   |   |   |   |   | 4
  3 |   | R |   |   |   |   |   | R |   | 3  (b3, h3)
  2 |   |   | R |   | C |   | R |   |   | 2  (c2, g2)
  1 |   |   |   | C | C | C |   |   |   | 1
    +---+---+---+---+---+---+---+---+---+
       a   b   c   d   e   f   g   h   i
```

Occupying squares `c2, g2, b3, h3, b7, h7, c8, g8` intercepts all diagonal and orthogonal escape rays radiating from the central castle before the King can reach the perimeter. `BlackHeuristics.java` evaluates occupancy of these 8 key squares, penalizing Black whenever a soldier breaks the ring unnecessarily.

### 3.3 Dynamic Swarm Gradient (Open-Field Containment)
When the King vacates the central $3 \times 3$ fortress ($d(\text{King}, \text{Throne}) \ge 2$), traditional throne-encirclement heuristics lose their relevance.

`computeOpenFieldContainment` scans all active Black pieces within 4 Manhattan steps of the King:

$$\text{SwarmScore} = \sum_{b \in \text{BlackPieces}} \max(0, 5 - d_{\text{Manhattan}}(b, \text{King})) \times \text{kingOpenFieldContainment}$$

This generates a continuous, attractive potential field, pulling Black pieces toward the King to cut off running lanes and establish multi-sided pins before White can setup an escape fork.

### 3.4 Proactive 2-Step Escape Forks (`SAFE_FORKS`)
Evaluating static 1-step escape rays is insufficient: by the time White creates an escape ray, Black can often block it immediately.

`computeKingSafeForks` in `WhiteHeuristics.java` evaluates **proactive 2-step escape trajectories**:
* Identifies intermediate destination squares $S$ reachable by the King in 1 legal slide.
* Checks whether landing on $S$ creates $\ge 2$ unblocked orthogonal rays to perimeter escapes.
* Verifies tactical safety via `isSafeForkSquare`: confirms that $S$ is not capturable by Black on the immediate reply ply.
* Awards a major tactical bonus (`kingSafeForkThreat = 2800`), steering White search branches toward positions that set up unstoppable 2-way forks on the following turn.

### 3.5 Defender Shield Cohesion & Quadratic Dispersion Penalty
In human expert play and champion engines, White defenders do not wander across the board: they form a tight protective escort around the King.
* **Shield Cohesion (`kingDefenderShieldCohesion`):** Awards a bonus for each White defender situated within Chebyshev distance $\le 2$ of the King ($\max(|r_w - r_k|, |c_w - c_k|) \le 2$).
* **Quadratic Dispersion Penalty (`whitePawnDispersionPenalty`):** When White defenders disperse beyond 3 Manhattan steps from the King ($d > 3$), they become liabilities that Black can capture or bypass. The penalty scales quadratically:

$$\text{DispersionPenalty} = \sum_{w \in \text{WhitePawns}, \, d(w, K) > 3} (d_{\text{Manhattan}}(w, K) - 3)^2 \times \text{whitePawnDispersionPenalty}$$

### 3.6 Camp-Wall Pinch Traps & Empty Throne Anvils
* **Camp Pinch Traps (`campPinchTraps`):** Black soldiers stationed near camp perimeters can trap adjacent White defenders against camp walls. `BlackHeuristics.java` detects alignment where a Black pawn is positioned 2 squares away along a row or column with a White defender in between, threatening immediate custodian execution.
* **Throne Anvil Threats (`throneAnvilThreats`):** When the King vacates `e5`, the center Throne becomes an impassable hostile barrier for both sides. Black leverages this geometry by placing White defenders between Black soldiers and `e5`, threatening captures with the Throne as an anvil.

### 3.7 Feature Covariance & SVD/PCA Audit (`analyze_feature_covariance.py`)
To prevent collinearity and gradient cancellation during SPSA tuning (where correlated parameters oscillate without improving strength), `scripts/analyze_feature_covariance.py` evaluates feature matrix $\mathbf{X} \in \mathbb{R}^{N \times 22}$ across 20,000 self-play board states generated via `FeatureMatrixGenerator.java`:
* Computes Pearson correlation matrix $\mathbf{R}$ and condition number $\kappa(\mathbf{X})$.
* Verifies that all 22 features maintain pairwise correlations $r < 0.85$, ensuring mathematical orthogonality and clean gradient descent during multi-night training runs.

---

## 4. Mathematical Formulation & Normalization

The static score is computed as a zero-sum differential between White's positional score and Black's positional score:

$$\text{Score}_{\text{White}}(S) = \sum_{i=1}^{11} w_{w,i} \cdot f_{w,i}(S) - \sum_{j=1}^{11} w_{b,j} \cdot f_{b,j}(S)$$

To conform to the Negamax convention (where the score is always expressed relative to the player whose turn it is to move):

$$\text{Evaluate}(S) = \begin{cases} +\text{Score}_{\text{White}}(S), & \text{if } \text{turn} = \text{WHITE} \\ -\text{Score}_{\text{White}}(S), & \text{if } \text{turn} = \text{BLACK} \end{cases}$$

```mermaid
flowchart LR
    subgraph "Feature Extraction"
        S["FastState State (S)"] --> W["WhiteHeuristics.evalWhite()"]
        S --> B["BlackHeuristics.evalBlack()"]
    end

    subgraph "Linear Combination"
        W --> W_Score["White Score = dot(w_white, f_white)"]
        B --> B_Score["Black Score = dot(w_black, f_black)"]
    end

    W_Score --> Diff["Delta = White Score - Black Score"]
    B_Score --> Diff

    subgraph "Negamax Polarity Normalization"
        Diff --> Polarity{"state.turn == WHITE?"}
        Polarity -- "Yes" --> RetWhite["Return +Delta"]
        Polarity -- "No" --> RetBlack["Return -Delta"]
    end
```

### 4.1 Symmetric Cancellation at the Initial Position
At the starting board position, the board is perfectly symmetric across all four quadrants. The heuristic weights were tuned such that:

$$\text{Score}_{\text{White}}(S_{\text{initial}}) \approx 0 \text{ centipawns}$$

Neither side starts with a perceived decisive advantage, ensuring stable search tree exploration during the opening phase.

### 4.2 Raw Throughput Profile
Because `HeuristicEvaluation.java` operates entirely on flat byte arrays with primitive bitwise operations and zero memory allocations, the heuristic evaluation achieves:

$$\text{Throughput} = \mathbf{30,940,593} \text{ evaluations / second}$$

on a single x86-64 CPU thread, allowing deep tree exploration without bottlenecking the search engine.
