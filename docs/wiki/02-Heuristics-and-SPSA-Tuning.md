# 02 — Heuristics & SPSA Parameter Tuning

This document describes the parameterized evaluation function of the **Antigravity** engine, the tactical intuition behind each feature, and the automated SPSA optimization pipeline used to train the weights across hundreds of thousands of games.

---

## 1. Evaluation Philosophy: High-Speed Linear vs Neural Networks

In game AI development, an essential design decision is the balance between **evaluation complexity** and **search depth**:

| Metric | Deep Neural Network / MLP | Parameterized Linear Heuristics (Antigravity) |
| :--- | :--- | :--- |
| **Throughput** | $\sim 150,000$ evaluations/second | **$30,000,000 - 85,000,000$ evaluations/second** |
| **Search Horizon** | Reaches Depth 3–4 within 45s | **Reaches Depth 6–8 within 45s** |
| **Horizon Effect Vulnerability**| Prone to tactical blindspots beyond 3 plies | **Tactically robust** via deep search + Quiescence |
| **Hardware Fit** | Requires GPU/SIMD matrix instructions | **100% CPU cache-friendly**, zero GPU needed |

Because the tournament VM is strictly CPU-only (4 vCPUs) and Ashton Tablut has sudden tactical checkmates (1-ply King escapes and corner pins), **raw search depth combined with domain-specific features decisively outperforms slower neural evaluators**.

The evaluation function computes a weighted sum from the perspective of the active player:
$$\text{Score}(S) = \begin{cases} \text{WhiteScore}(S) - \text{BlackScore}(S) & \text{if White to move} \\ \text{BlackScore}(S) - \text{WhiteScore}(S) & \text{if Black to move} \end{cases}$$

---

## 2. The 17 Parameterized Features

```
          WHITE OBJECTIVES                             BLACK OBJECTIVES
  ┌──────────────────────────────┐             ┌──────────────────────────────┐
  │ 1. White Material            │             │ 9. Black Material            │
  │ 2. King 1-Step Escape Fork   │             │ 10. Rhombus Blockade (8-ring)│
  │ 3. King 2-Step Escape Fork   │    VS       │ 11. Keep the Fronts (Camps)  │
  │ 4. Escape Corridors          │             │ 12. Camp Preservation        │
  │ 5. Open Escape Corridors     │             │ 13. King Encirclement (1..4) │
  │ 6. Pawn Vulnerability        │             │ 14. Perimeter Distance Ring  │
  │ 7. King Distance to Win      │             │ 15. Connected Pawn Links     │
  │ 8. Diamond Center Formations │             │ 16. Barrier & Camp Anvils    │
  └──────────────────────────────┘             │ 17. Open-Field Swarm Gradient│
                                               └──────────────────────────────┘
```

### 2.1 White Heuristic Features (`WhiteHeuristics.java`)
1. **`whiteMaterial` ($w_1$):** Number of living White soldiers. Asymmetric weight reflecting the precious nature of White's 8 pawns against 16 Black pieces.
2. **`kingEscapes1Step` ($w_2$):** Detects if the King has two or more unblockable 1-step escape paths simultaneously (an unstoppable win fork).
3. **`kingEscapes2Step` ($w_3$):** Rewards creating 2-step escape forks that force Black into defensive concessions.
4. **`kingEscapeCorridors` ($w_4$):** Measures clear orthogonal lines connecting the King to the 20 perimeter win squares.
5. **`kingOpenCorridors` ($w_5$):** Bonus for open corridors that contain zero opposing Black soldiers.
6. **`whitePawnVulnerability` ($w_6$):** Penalty for White pawns standing in lines flanked by hostile camps or Black soldiers without defender support.
7. **`kingSafety` ($w_7$):** Reward for reducing the true minimum Manhattan distance from the King to any of the 20 perimeter win squares (`MIN_DISTANCE_TO_ESCAPE`).
8. **`diamondCenterControl` ($w_8$):** Strategic bonus for occupying the diamond fortress $\{d3, f4, d6, f7\}$, maintaining king mobility while shielding the throne.

### 2.2 Black Heuristic Features (`BlackHeuristics.java`)
9. **`blackMaterial` ($w_9$):** Number of living Black soldiers. Preserves numerical advantage.
10. **`blackRhombusBlockade` ($w_{10}$):** Rewards soldiers occupying the 8 outer perimeter choke points $\{c2, g2, b3, h3, b7, h7, c8, g8\}$ which systematically close off all corner escape corridors.
11. **`keepTheFronts` ($w_{11}$):** Penalizes premature evacuation of camp doorway tiles (`e2, e8, b5, h5`), preventing White soldiers from sliding through the edges.
12. **`campPreservation` ($w_{12}$):** Reward for retaining soldiers in their defensive camp quadrants until deployment is tactically necessary.
13. **`kingEncirclement` ($w_{13}$):** Rewards surrounding the King on 1, 2, 3, or 4 sides depending on whether the King is on the Throne, adjacent to the Throne, or in open field.
14. **`kingContainment` ($w_{14}$):** Penalizes the King for being close to board edges, pulling the King towards the heavily contested center.
15. **`connectedBlockades` ($w_{15}$):** Rewards orthogonal and diagonal chains of Black soldiers that form contiguous walls impervious to White rook-slides.
16. **`anvilPins` ($w_{16}$):** Rewards pinning White pieces against hostile camp barriers or the empty Throne.
17. **`kingOpenFieldContainment` ($w_{17}$):** A continuous swarm gradient activated whenever the King breaks free from the center ($r, c \notin [2..6]$). Computes aggregate inverse Manhattan distances $\sum \frac{1}{\text{dist}(\text{Black}_i, \text{King}) + 1}$ to pull all Black soldiers into a relentless closing net.

---

## 3. SPSA Optimization Pipeline

Tuning 17 coupled heuristic weights by hand is mathematically intractable due to non-linear interactions. We apply **Simultaneous Perturbation Stochastic Approximation (SPSA)** (Spall, 1992).

### 3.1 The SPSA Gradient Estimation
Instead of evaluating $2 \times 17 = 34$ matches per gradient step (standard finite differences), SPSA perturbs **all 17 dimensions simultaneously** using a random Bernoulli vector $\Delta_k \in \{-1, +1\}^{17}$:
$$\theta_k^+ = \theta_k + c_k \Delta_k, \quad \theta_k^- = \theta_k - c_k \Delta_k$$
Only **2 evaluations** ($N$ self-play matches at $\theta^+$ vs $\theta^-$) are required to estimate the full gradient:
$$\hat{g}_k(\theta_k) = \frac{y(\theta_k^+) - y(\theta_k^-)}{2 c_k} \Delta_k^{-1}$$
The weight vector updates via:
$$\theta_{k+1} = \theta_k + a_k \hat{g}_k(\theta_k)$$

```
                                  Current Weights θ_k
                                           │
                   Perturb All Dimensions with Bernoulli Vector Δ_k
                   ┌───────────────────────┴───────────────────────┐
                   ▼                                               ▼
         Positive Vector θ_k^+                           Negative Vector θ_k^-
                   └───────────────────────┬───────────────────────┘
                                           │
                            N Self-Play Games (Depth 4)
                            Headless Tournament Simulation
                                           │
                               Estimate Gradient g_k
                                           │
                              Update Weights θ_{k+1}
                                           │
                         Atomic Checkpoint (checkpoint_weights.json)
```

### 3.2 The Warm-Restart Innovation
Standard SPSA uses power-law decay schedules:
$$a_k = \frac{a}{(k + 1 + A)^\alpha}, \quad c_k = \frac{c}{(k + 1)^\gamma}$$
After a long training session (e.g. 11,700 iterations), $a_k$ shrinks so small ($< 0.028$) that resuming training effectively freezes learning.
Our implementation introduces `--warm-restart`, resetting the decay counter $k$ whenever transitioning to a new phase or depth while preserving the accumulated weights.

### 3.3 Automated Multi-Night Training: `train_overnight.py`
The script [`scripts/train_overnight.py`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/scripts/train_overnight.py) manages a 2-stage overnight run:
* **Stage 1 (Warm-Up, 300 iterations):** Depth 3, stage `positional`, fast stabilization of newly added weights (e.g. weight 17).
* **Stage 2 (Deep Convergence, 1500+ iterations):** Depth 4, stage `all`, $N=80$ games per step, full 17-parameter joint refinement.
* **Anytime Graceful Exit:** Catches `Ctrl+C` / `SIGINT` cleanly, committing the latest checkpoint to disk without corrupting files.
