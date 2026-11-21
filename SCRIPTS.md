# Guía Operativa y Catálogo de Scripts — DajeGeppetto Tablut Engine

Manual centralizado de referencia de todos los scripts, comandos de ejecución, pipelines de entrenamiento y suites de prueba del proyecto. Diseñado para desacoplar las instrucciones operativas de [`README.md`](README.md) y la [Wiki técnica](docs/wiki/Home.md).

---

## 1. Quick Reference (Cheat Sheet)

| Tarea / Acción | Comando |
| :--- | :--- |
| **Pipeline nocturno completo Fase 17 (Depth 5/6 CRN SPSA)** | `python scripts/overnight_60s_pipeline.py --skip-server` |
| **Entrenamiento nocturno 2-fases SPSA (`train_overnight.py`)** | `python scripts/train_overnight.py --auto-apply` |
| **Inyectar pesos de checkpoint a Java** | `python scripts/apply_weights.py` |
| **Torneo multi-versión (round-robin commits Git + LOCAL)** | `.\scripts\tournament_versions.ps1 -Commits @("f19e060","90fe1c0","LOCAL") -GamesPerPair 2 -Timeout 15` |
| **Match inter-versiones (HEAD vs versión anterior)** | `.\scripts\match_versions.ps1 -Games 2 -Timeout 15 -Commit HEAD~1` |
| **Torneo contra rivales (BrAInmates, THoR, Luca)** | `.\scripts\tournament_vs_opponents.ps1 -Opponent all -Games 2 -Timeout 45` |
| **Torneo solo contra BrAInmates (Campeón 2020)** | `.\scripts\tournament_vs_opponents.ps1 -Opponent brainmates -Games 4 -Timeout 45` |
| **Torneo vs Luca (Red Neuronal / NNUE)** | `.\scripts\tournament_vs_luca.ps1 -Games 10 -Timeout 60` |
| **Partida oficial con GUI vs Luca (45s)** | `.\scripts\match_official.ps1 -Role WHITE -Opponent LUCA -Timeout 45 -EnableGui` |
| **Partida oficial rápida vs RandomPlayer (10s)** | `.\scripts\match_official.ps1 -Role WHITE -Opponent RANDOM -Timeout 10` |
| **Test rápido de humo (end-to-end)** | `.\scripts\test_match.ps1 -Role WHITE -Timeout 10` |
| **Ablation Benchmark en RAM (Pesos actuales vs baseline)** | `python scripts/run_tournament.py --games 100 --depth 3` |
| **Minar aperturas desde logs de partidas** | `python scripts/mine_openings.py --logs-dir logs --max-plies 6` |
| **Auditoría de ortogonalidad y covarianza SVD** | `python scripts/analyze_feature_covariance.py --states 1000 --generate` |
| **Compilar el motor Java (Windows PowerShell, UTF-8)** | `javac -encoding UTF-8 -cp "TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*" -d bin src\tablut\engine\*.java` |
| **Ejecución oficial del agente (Windows)** | `.\runmyplayer.ps1 -Role WHITE -Timeout 60 -ServerIp localhost` |
| **Ejecución oficial del agente (Linux / VM)** | `./runmyplayer.sh WHITE 60 localhost` |
| **Certificación local completa pre-entrega** | `.\scripts\verify_local.ps1` |
| **Certificación dentro de la VM (Bash)** | `./scripts/verify_vm.sh` |
| **Limpieza y purgado previo a exportar OVA** | `./scripts/clean_vm.sh` |
| **Generar manifiesto de entrega y hash SHA-256** | `.\scripts\generate_submission_manifest.ps1 -OvaPath <fichero.ova>` |

---

## 2. Compilación y Build

El motor está implementado en Java puro (Java 9+ por uso de `VarHandle` en `TranspositionTable.java`, testeado en Java 17/21) con dependencias exclusivas en el referee server y las librerías del starter kit oficial en [`TablutCompetition-master/`](TablutCompetition-master).

### Windows (PowerShell)
```powershell
javac -encoding UTF-8 -cp "TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*" -d bin src\tablut\engine\*.java
```

### Linux / VirtualBox VM (Bash)
```bash
javac -encoding UTF-8 -cp "TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*" -d bin src/tablut/engine/*.java
```

> [!NOTE]
> Todos los `.class` se compilan en la carpeta `bin/`. La estructura de paquetes es `tablut.engine.*`.

---

## 3. Entrenamiento y Optimización de Pesos Heurísticos (SPSA)

El evaluador lineal utiliza **24 pesos posicionales y tácticos** (**21 dimensiones ortogonales activas** tras congelar las 3 dependencias lineales exactas `COLLINEAR_INDICES = {1, 9, 13}`: `blackCaptured`, `whiteCaptured`, `kingContainment`) optimizados mediante **CRN SPSA (Simultaneous Perturbation Stochastic Approximation con Common Random Numbers)** sobre el simulador headless en RAM ([`HeadlessTournament.java`](src/tablut/engine/HeadlessTournament.java), sin coste de red ni GUI).

### 3.1 Pipeline Completo Fase 17 (`scripts/overnight_60s_pipeline.py`)
Pipeline multi-etapa a profundidad 5–6 con semillas pareadas CRN, compuerta de no-regresión de Negras (`black_ok`), *shootout* multi-resolución (Depth 5, `750ms`, `1200ms`), aplicación certificada sobre las **12 suites de regresión Java** (con *rollback* automático si falla un test) y validación opcional a 60s en `Server.jar`:

```powershell
# Pipeline nocturno completo (Stage 1 D5 white_focus -> 2A D6 white_focus -> 2B D5 all -> 2C D6 all -> Shootout -> Apply + Tests):
python scripts/overnight_60s_pipeline.py --skip-server

# Pipeline completo incluyendo torneo de verificación a 60s en Server.jar:
python scripts/overnight_60s_pipeline.py

# Prueba rápida de humo (1 iteración por etapa) o dry-run:
python scripts/overnight_60s_pipeline.py --smoke-test --skip-server
python scripts/overnight_60s_pipeline.py --dry-run
```

### 3.2 Pipeline de Entrenamiento 2-Fases (`scripts/train_overnight.py`)
Diseñado para ejecuciones desatendidas en dos fases con captura segura de `Ctrl+C`:

* **Fase 1 (Warm-Up & Targeted Acclimatization):**
  - **Profundidad de búsqueda:** Depth 5 + Quiescence (`--warmup-depth 5`).
  - **Partidas por paso:** 80 partidas CRN con alternancia estricta de color (`--games-phase1 80`).
  - **Parámetros:** Sub-bloque objetivo configurable (`--warmup-stage`, por defecto `white_focus`).
  - **Duración:** 300 iteraciones (`--warmup-iters 300`).
* **Fase 2 (Global Covariance Fine-Tuning):**
  - **Profundidad de búsqueda:** Depth 6 + Quiescence (`--depth 6`).
  - **Partidas por paso:** 40 partidas CRN (`--games-phase2 40`).
  - **Parámetros:** Los 21 parámetros ortogonales activos (`all` con `--freeze-collinear`).
  - **Duración:** Continua indefinidamente (modo anytime, o acotada con `--max-iters-phase2`).

#### Comandos recomendados:

```powershell
# 1. Modo nocturno completo: Fase 1 (Depth 5 white_focus) -> Fase 2 (Depth 6 all)
python scripts/train_overnight.py --auto-apply

# 2. Modo directo a Fase 2 global (los 21 parámetros activos a Depth 5 o 6):
python scripts/train_overnight.py --skip-warmup --depth 5 --auto-apply

# 3. Calentamiento focalizado en las heurísticas compuestas:
python scripts/train_overnight.py --warmup-stage tactical_compound --warmup-iters 200 --auto-apply

# 4. Forzar número de hilos o comprobar configuración en dry-run:
python scripts/train_overnight.py --threads 8 --auto-apply
python scripts/train_overnight.py --dry-run
```

#### Parámetros disponibles (`train_overnight.py`):
* `--warmup-iters <N>`: Iteraciones para la Fase 1 (defecto: `300`).
* `--warmup-stage <STAGE>`: Sub-etapa para Fase 1 (`material`, `positional`, `micro`, `tactical_compound`, `white_focus`, `all`; defecto: `white_focus`).
* `--warmup-depth <D>`: Profundidad en Fase 1 (defecto: `5`).
* `--games-phase1 <G>` / `--games-phase2 <G>`: Partidas por paso en Fase 1 (`80`) y Fase 2 (`40`).
* `--skip-warmup`: Salta la Fase 1 e inicia directamente en Fase 2.
* `--depth <D>`: Profundidad de búsqueda minimax en Fase 2 (defecto: `6`).
* `--threads <T>`: Hilos de trabajo en paralelo (defecto: todos los vCPUs lógicos).
* `--auto-apply`: Aplica automáticamente los mejores pesos a [`HeuristicWeights.java`](src/tablut/engine/HeuristicWeights.java) y recompila los binarios al terminar.

---

### 3.3 Optimizador SPSA Granular (`scripts/train_spsa.py`)
El motor de bajo nivel subyacente. Se utiliza para ejecuciones manuales con control total de hiperparámetros de optimización estocástica y validación CRN.

```powershell
# Ejecutar optimización manual a Depth 5 en 8 hilos sobre white_focus:
python scripts/train_spsa.py --stage white_focus --depth 5 --games 80 --threads 8

# Reanudar desde un checkpoint personalizado con warm-restart y límite de tiempo:
python scripts/train_spsa.py --resume --warm-restart --checkpoint logs/checkpoints_overnight/stage2b_d5_all.json --stage all --depth 5 --games 80 --max-minutes 90
```

#### Parámetros clave (`train_spsa.py`):
* `--stage <material|positional|micro|tactical_compound|white_focus|all>`: Bloque de parámetros a perturbar (defecto: `all`).
* `--depth <N>`: Profundidad de búsqueda en simulación (defecto: `3`).
* `--games <N>`: Partidas por paso de gradiente (defecto: `60`, siempre par para balance White/Black CRN).
* `--threads <N>`: Hilos en paralelo.
* `--max-iterations <N>`: Límite de iteraciones de sesión (defecto: `0`, infinito hasta interrupción).
* `--max-minutes <M>`: Límite de tiempo de sesión en minutos (defecto: `0`, desactivado).
* `--checkpoint <fichero>`: Fichero JSON atómico (defecto: `checkpoint_weights.json`).
* `--resume` / `--warm-restart`: Carga el checkpoint anterior y reinicia el decaimiento de la tasa de aprendizaje respecto a la sesión actual.
* `--freeze-collinear` / `--no-freeze-collinear`: Congela las 3 dimensiones colineales SVD (`{1, 9, 13}`, activo por defecto).
* `--eval-interval <N>` / `--eval-games <G>`: Frecuencia (`50`) y tamaño (`80`) de la compuerta CRN de promoción EMA + `black_ok`.
* `--learning-rate <a>` / `--perturbation <c>`: Constantes de aprendizaje `a` (defecto: `8.0`) y perturbación `c` (defecto: `1.0`).

---

### 3.4 Inyector de Pesos a Java (`scripts/apply_weights.py`)
Lee el checkpoint generado ([`checkpoint_weights.json`](checkpoint_weights.json)) e inyecta los valores optimizados directamente en el código fuente de [`HeuristicWeights.java`](src/tablut/engine/HeuristicWeights.java), recompilando el proyecto inmediatamente.

```powershell
# Aplicar el mejor vector de pesos (best_weights) y recompilar:
python scripts/apply_weights.py

# Aplicar los pesos activos (active_weights, última iteración) en vez del mejor histórico:
python scripts/apply_weights.py --use-active

# Visualizar el diff de pesos sin tocar ficheros ni recompilar:
python scripts/apply_weights.py --dry-run
```

---

## 4. Partidas Individuales y Validación Oficial

Ejecutan el servidor oficial ([`Server.jar`](TablutCompetition-master/Tablut/Executables/Server.jar)), el agente DajeGeppetto y el oponente sobre sockets TCP en puertos 5800/5801.

### 4.1 Partida Oficial Parametrizable (`scripts/match_official.ps1`)
Orquesta una partida completa gestionando el ciclo de vida de los procesos, redirección de logs en `logs/` y parada forzada limpia al terminar.

```powershell
# Jugar como WHITE contra RandomPlayer con timeout oficial de 45s:
.\scripts\match_official.ps1 -Role WHITE -Opponent RANDOM -Timeout 45

# Jugar como BLACK contra el motor neuronal de Luca con timeout de 45s:
.\scripts\match_official.ps1 -Role BLACK -Opponent LUCA -Timeout 45

# Jugar como WHITE contra Luca visualizando la interfaz gráfica oficial:
.\scripts\match_official.ps1 -Role WHITE -Opponent LUCA -Timeout 45 -EnableGui

# Partida rápida de prueba (10s de timeout):
.\scripts\match_official.ps1 -Role WHITE -Opponent RANDOM -Timeout 10
```

#### Parámetros:
* `-Role <WHITE | BLACK>`: Rol de DajeGeppetto (defecto: `WHITE`).
* `-Opponent <RANDOM | LUCA | DAJEGEPPETTO>`: Oponente a lanzar (defecto: `RANDOM`).
* `-Timeout <segundos>`: Tiempo límite por movimiento en segundos (defecto: `10`).
* `-EnableGui`: Abre la ventana GUI de Java del servidor de arbitraje oficial.

### 4.2 Test Rápido de Humo (`scripts/test_match.ps1`)
Comprobación mínima de sanidad end-to-end contra `RandomPlayer.jar`.

```powershell
# Validar conectividad de White:
.\scripts\test_match.ps1 -Role WHITE -Timeout 10

# Validar conectividad de Black:
.\scripts\test_match.ps1 -Role BLACK -Timeout 10
```

---

## 5. Torneos y Benchmarking contra Rivales

### 5.1 Torneo Multi-Rival (`scripts/tournament_vs_opponents.ps1`)
Harness de torneo automatizado que enfrenta a DajeGeppetto contra los motores de referencia de la competición UNIBO disponibles en [`competitors/`](competitors) y [`team/`](team). Garantiza simetría de color (partidas pares alternando White/Black).

* **Rivales configurados:**
  1. `brainmates`: **BrAInmates** (Campeón absoluto del torneo UNIBO 2020).
  2. `thor`: **THoR** (3º puesto del torneo UNIBO 2020).
  3. `luca`: **Luca's MLP Engine** (motor neuronal con 40 features y Lazy SMP).
  4. `all`: Ejecuta rondas consecutivas contra todos los motores anteriores.

```powershell
# Torneo completo contra TODOS los rivales (2 partidas por rival: 1 White, 1 Black, 45s):
.\scripts\tournament_vs_opponents.ps1 -Opponent all -Games 2 -Timeout 45

# Benchmark intensivo contra el campeón BrAInmates (4 partidas, 45s):
.\scripts\tournament_vs_opponents.ps1 -Opponent brainmates -Games 4 -Timeout 45

# Benchmark contra THoR (2 partidas, 45s):
.\scripts\tournament_vs_opponents.ps1 -Opponent thor -Games 2 -Timeout 45

# Benchmark rápido contra Luca (10s por movimiento):
.\scripts\tournament_vs_opponents.ps1 -Opponent luca -Games 2 -Timeout 10
```

#### Parámetros:
* `-Opponent <brainmates | thor | luca | all>`: Rival a evaluar (defecto: `all`).
* `-Games <N>`: Número total de partidas (defecto: `2`).
* `-Timeout <segundos>`: Timeout por movimiento en segundos (defecto: `45`).
* `-EnableGui`: Muestra la interfaz gráfica del referee server.

---

### 5.2 Torneo Específico vs Luca (`scripts/tournament_vs_luca.ps1`)
Runner optimizado para matches directos contra la arquitectura de Luca (evaluación NNUE incremental o CNN v4 híbrida, ordenamiento `order=rec` y 4 hilos Lazy SMP). Guarda telemetría en tiempo real en `logs/luca_tournament_summary.txt`.

```powershell
# Modo nocturno recomendado: 10 partidas alternando colores a 60s contra NNUE (t1500000.nnue):
.\scripts\tournament_vs_luca.ps1 -Games 10 -Timeout 60

# Torneo largo de 20 partidas a 60s:
.\scripts\tournament_vs_luca.ps1 -Games 20 -Timeout 60

# Torneo contra la red CNN previa de 1.5M pasos (cnn_t1500000_v4.txt):
.\scripts\tournament_vs_luca.ps1 -Games 10 -Timeout 60 -LucaWeights cnn_t1500000_v4.txt

# Torneo rápido de 2 partidas con GUI activada:
.\scripts\tournament_vs_luca.ps1 -Games 2 -Timeout 60 -EnableGui
```

#### Parámetros:
* `-Games <N>`: Número total de partidas con alternancia estricta de color White/Black (defecto: `10`).
* `-Timeout <segundos>`: Tiempo límite por movimiento en segundos (defecto: `60`).
* `-MaxPlies <N>`: Límite máximo de semi-jugadas por partida antes de adjudicar empate por fortaleza (defecto: `80`, evita bucles infinitos de peones).
* `-LucaAlgo <negamax | mcts>`: Motor de búsqueda de Luca (defecto: `negamax`).
* `-LucaWeights <fichero>`: Fichero de pesos (defecto: `pesi_nnue/t1500000.nnue`; admite también `cnn_t1500000_v4.txt`).
* `-EvalBackend <auto | nnue | cnn | mlp | simple>`: Backend de inferencia de Luca (defecto: `auto`).
* `-EnableGui`: Abre la ventana gráfica oficial de `Server.jar`.

---

### 5.3 Ablation Benchmark de Pesos en RAM (`scripts/run_tournament.py`)
Compara el rendimiento de dos conjuntos de pesos heurísticos en partidas simuladas en RAM (sin servidor de red ni JVMs externas), midiendo el incremento de Elo y significancia estadística ($p$-value, intervalo de confianza al 95%).

```powershell
# Evaluar pesos actuales contra la baseline original (100 partidas a Depth 3):
python scripts/run_tournament.py --games 100 --depth 3

# Evaluación profunda a Depth 4 en 8 hilos:
python scripts/run_tournament.py --games 200 --depth 4 --threads 8

# Comparar pesos contra un checkpoint guardado:
python scripts/run_tournament.py --checkpoint checkpoint_weights.json --games 100 --depth 3
```

---

### 5.4 Match y Torneo Inter-Versiones (`scripts/tournament_versions.ps1` & `scripts/match_versions.ps1`)
Permiten comparar la versión local (`LOCAL`) contra uno o varios commits históricos de Git compilados de forma aislada sobre el `Server.jar` oficial:

```powershell
# Torneo round-robin entre commits históricos y la versión local actual:
.\scripts\tournament_versions.ps1 -Commits @("f19e060", "90fe1c0", "LOCAL") -GamesPerPair 2 -Timeout 15

# Match rápido de 2 partidas a 15s por movimiento contra el commit anterior:
.\scripts\match_versions.ps1 -Games 2 -Timeout 15

# Comparar contra un commit específico con timeout oficial de 45s o GUI:
.\scripts\match_versions.ps1 -Commit a1b2c3d -Games 2 -Timeout 45
.\scripts\match_versions.ps1 -Games 2 -Timeout 15 -EnableGui
```

#### Parámetros (`tournament_versions.ps1`):
* `-Commits <string[]>`: Lista de commits Git y/o `"LOCAL"` (defecto: `@("d31ba35", "f19e060", "LOCAL")`).
* `-GamesPerPair <N>`: Partidas por enfrentamiento (siempre par para alternancia de color; defecto: `2`).
* `-Timeout <segundos>`: Tiempo límite por movimiento (defecto: `60`).
* `-MaxPlies <N>`: Límite de semi-jugadas antes de adjudicar empate (defecto: `150`).
* `-KeepBuilds`: Conserva los binarios compilados en `build_versions/`.

#### Parámetros (`match_versions.ps1`):
* `-Commit <hash | ref>`: Commit o referencia de Git para la versión rival (defecto: `HEAD~1`).
* `-Games <N>`: Número de partidas (debe ser par para alternancia de color; defecto: `2`).
* `-Timeout <segundos>`: Tiempo límite por movimiento en segundos (defecto: `15`).
* `-EnableGui`: Muestra la ventana de la interfaz gráfica oficial.

---

## 6. Ejecución Oficial del Agente (Reglas UNIBO / VM)

Conforme a la especificación obligatoria del torneo UNIBO, el agente debe invocarse mediante los parámetros posicionales `<ROLE> <TIMEOUT_SECONDS> <SERVER_IP>`.

### En la Máquina Virtual de Competición (Linux)
```bash
# Otorgar permisos de ejecución (si no los tuviera):
chmod +x runmyplayer.sh

# Ejecutar como White con timeout de 60s conectando a localhost:
./runmyplayer.sh WHITE 60 localhost

# Ejecutar como Black con timeout de 30s conectando a una IP remota:
./runmyplayer.sh BLACK 30 192.168.1.50
```

### En Entorno Windows Local (PowerShell)
```powershell
# Ejecución estándar de desarrollo:
.\runmyplayer.ps1 -Role WHITE -Timeout 60 -ServerIp localhost

# Parámetros posicionales idénticos a bash:
.\runmyplayer.ps1 BLACK 45 localhost
```

---

## 7. Minería de Aperturas y Auditoría Estadística

### 7.1 Analizador de Logs y Extractor de Aperturas (`scripts/mine_openings.py`)
Examina los logs del servidor de arbitraje en `logs/*_server.log`, reconstruye los movimientos efectuados en los primeros $N$ plies, y calcula los porcentajes de victoria de White y Black para identificar variantes de apertura ganadoras.

```powershell
# Analizar aperturas de los primeros 6 plies en todos los logs:
python scripts/mine_openings.py --logs-dir logs --max-plies 6

# Filtrar líneas con al menos 2 apariciones:
python scripts/mine_openings.py --logs-dir logs --max-plies 8 --min-frequency 2
```

### 7.2 Auditoría de Ortogonalidad y Covarianza SVD (`scripts/analyze_feature_covariance.py`)
Herramienta de análisis matemático de los 24 vectores de características en partidas simuladas en RAM ([`FeatureMatrixGenerator.java`](src/tablut/engine/FeatureMatrixGenerator.java)):
```powershell
# Simular 1.000 estados y generar informe de correlación Pearson y espectro SVD:
python scripts/analyze_feature_covariance.py --states 1000 --generate

# Analizar un CSV existente con umbral de correlación personalizado:
python scripts/analyze_feature_covariance.py --csv feature_matrix.csv --threshold 0.75
```

---

## 8. Batería de Tests Unitarios y Verificación (Java — 17 Suites Totales)

El motor cuenta con **17 suites unitarias y de regresión** en `src/tablut/engine/` (**8 suites core** ejecutadas por `verify_local.ps1` / `verify_vm.sh`, y **12 suites de regresión** ejecutadas por `overnight_60s_pipeline.py`):

### Ejecución de tests en Windows (PowerShell)
```powershell
$cp = "bin;TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*"

# 1. Verificación de reglas oficiales Ashton Tablut (vs GameAshtonTablut oficial) [Core]:
java -cp $cp tablut.engine.RuleVerificationTest

# 2. Test de rendimiento por estado y conteo de nodos (PerftTest, >85M NPS) [Core]:
java -cp $cp tablut.engine.PerftTest

# 3. Test del motor de búsqueda Negamax / IDS / Time Management [Core]:
java -cp $cp tablut.engine.SearchEngineTest

# 4. Test del motor paralelo Lazy SMP (4 vCPUs) [Core]:
java -cp $cp tablut.engine.ParallelSearchEngineTest

# 5. Test de la tabla de transposición lockless VarHandle Hyatt XOR [Core]:
java -cp $cp tablut.engine.TranspositionTableTest

# 6. Test del libro de aperturas con simetría diedral D4 (117 canónicos / 936 estados) [Core]:
java -cp $cp tablut.engine.OpeningBookTest

# 7. Test de Quiescence Search y extensiones tácticas:
java -cp $cp tablut.engine.QuiescenceSearchTest

# 8. Test de ordenación de movimientos (PV, Hash, Wins, Killers, History):
java -cp $cp tablut.engine.MoveOrdererTest

# 9. Test del evaluador heurístico lineal de 24 parámetros [Pipeline]:
java -cp $cp tablut.engine.HeuristicEvaluationTest

# 10. Test de regresión de Fase 6 (GHI safety, Lazy ordering, Citadel geometry):
java -cp $cp tablut.engine.Phase6EnhancementsTest

# 11. Test de regresión de Fase 7 (PVS, Aspiration Windows, Pins):
java -cp $cp tablut.engine.Phase7OptimizationsTest

# 12. Test de regresión de Fase 9 (D4 expandido, corte mate temprano, gradiente enjambre):
java -cp $cp tablut.engine.Phase9OptimizationsTest

# 13. Test de regresión de Fase 12 (cortes analíticos O(1), cohesión de escudo, dispersión cuadrática, b_eff dinámico, extensiones singulares PVS) [Pipeline]:
java -cp $cp tablut.engine.Phase12OptimizationsTest

# 14. Test de regresión de Fase 13 (sanitización de apertura trono, SAFE_FORKS 2-pasos, anti-colgado raíz, TT bucket 2-slots 2^23) [Pipeline]:
java -cp $cp tablut.engine.Phase13RefinementsTest

# 15. Test de hardening táctico de Fase 14 (P1 trono, P2 LMR, P3 QS dist<=2, P4 camp corners, P5 aperturas) [Pipeline]:
java -cp $cp tablut.engine.Phase14TacticalHardeningTest

# 16. Test de optimizaciones finales de Fase 15 (best-worker adoption, lastCapturePly, loop fusion, PROTECTION_KING, incremental anti-hanging) [Core]:
java -cp $cp tablut.engine.Phase15FinalOptimizationsTest

# 17. Test de recalibración de Fase 16 (global D4, dynamic QS delta, LMR guard, CRN 50.00%, isBetterWorkerResult, zero-centered contempt) [Core]:
java -cp $cp tablut.engine.Phase16RecalibrationTest
```

### Ejecución de tests en Linux / macOS (Bash)
```bash
CP="bin:TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*"

# Ejemplo:
java -cp "$CP" tablut.engine.RuleVerificationTest
java -cp "$CP" tablut.engine.Phase16RecalibrationTest
```

---

## 9. Certificación y Despliegue en la Máquina Virtual (Pre-Submission)

Scripts para validar los **10 invariantes oficiales** de la competición antes de exportar el appliance `.ova`.

### 9.1 Certificación Local en Windows (`scripts/verify_local.ps1`)
Ejecuta la pipeline de compilación limpia UTF-8, chequeo de inmutabilidad de ficheros del starter, conversión de finales de línea a POSIX LF, y ejecución de las **8 suites core** + partida smoke:
```powershell
# Certificación completa con partida rápida contra RandomPlayer:
.\scripts\verify_local.ps1

# Certificación rápida (omitiendo la partida smoke):
.\scripts\verify_local.ps1 -SkipMatch
```

### 9.2 Certificación dentro de la VM Debian (`scripts/verify_vm.sh`)
Ejecutar dentro de la máquina virtual oficial en `/home/tablut/tablut`:
```bash
cd /home/tablut/tablut
chmod +x scripts/verify_vm.sh
./scripts/verify_vm.sh
```

### 9.3 Limpieza y Purgado Previo a la Exportación (`scripts/clean_vm.sh`)
Ejecutar dentro de la VM justo antes de apagarla para exportar el `.ova`. Recompila `bin/` en UTF-8, borra logs, scratch, caché de apt y vacía el historial de bash:
```bash
cd /home/tablut/tablut
chmod +x scripts/clean_vm.sh
./scripts/clean_vm.sh
```

### 9.4 Generador de Manifiesto y Checksum SHA-256 (`scripts/generate_submission_manifest.ps1`)
Ejecutar en la máquina anfitriona tras exportar el fichero OVA desde VirtualBox. Calcula el hash SHA-256, el tamaño del fichero y formatea el archivo `submission_manifest.txt` listo para subir a Virtuale:
```powershell
.\scripts\generate_submission_manifest.ps1 -OvaPath "DajeGepetto_Tablut_Submission.ova" -OneDriveUrl "https://liveunibo-my.sharepoint.com/:u:/g/personal/..."
```

