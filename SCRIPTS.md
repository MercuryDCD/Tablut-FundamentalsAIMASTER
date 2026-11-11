# Guía Operativa y Catálogo de Scripts — DajeGeppetto Tablut Engine

Manual centralizado de referencia de todos los scripts, comandos de ejecución, pipelines de entrenamiento y suites de prueba del proyecto. Diseñado para desacoplar las instrucciones operativas de [`README.md`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/README.md) y [`GEMINI.md`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/GEMINI.md).

---

## 1. Quick Reference (Cheat Sheet)

| Tarea / Acción | Comando |
| :--- | :--- |
| **Entrenamiento nocturno SPSA (Recomendado)** | `python scripts/train_overnight.py --auto-apply` |
| **Entrenamiento nocturno (saltar calentamiento)** | `python scripts/train_overnight.py --skip-warmup --depth 4 --auto-apply` |
| **Inyectar pesos de checkpoint a Java** | `python scripts/apply_weights.py` |
| **Torneo contra rivales (BrAInmates, THoR, Luca)** | `.\scripts\tournament_vs_opponents.ps1 -Opponent all -Games 2 -Timeout 45` |
| **Torneo solo contra BrAInmates (Campeón 2020)** | `.\scripts\tournament_vs_opponents.ps1 -Opponent brainmates -Games 4 -Timeout 45` |
| **Torneo vs Luca (Red Neuronal / NNUE)** | `.\scripts\tournament_vs_luca.ps1 -Games 10 -Timeout 60` |
| **Match inter-versiones (HEAD vs versión anterior)** | `.\scripts\match_versions.ps1 -Games 2 -Timeout 15 -Commit HEAD~1` |
| **Partida oficial con GUI vs Luca (45s)** | `.\scripts\match_official.ps1 -Role WHITE -Opponent LUCA -Timeout 45 -EnableGui` |
| **Partida oficial rápida vs RandomPlayer (10s)** | `.\scripts\match_official.ps1 -Role WHITE -Opponent RANDOM -Timeout 10` |
| **Test rápido de humo (end-to-end)** | `.\scripts\test_match.ps1 -Role WHITE -Timeout 10` |
| **Ablation Benchmark en RAM (Pesos actuales vs baseline)** | `python scripts/run_tournament.py --games 100 --depth 3` |
| **Minar aperturas desde logs de partidas** | `python scripts/mine_openings.py --logs-dir logs --max-plies 6` |
| **Auditoría de ortogonalidad y covarianza SVD** | `python scripts/analyze_feature_covariance.py --states 1000 --generate` |
| **Compilar el motor Java (Windows PowerShell)** | `javac -cp "TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*" -d bin src\tablut\engine\*.java` |
| **Ejecución oficial del agente (Windows)** | `.\runmyplayer.ps1 -Role WHITE -Timeout 60 -ServerIp localhost` |
| **Ejecución oficial del agente (Linux / VM)** | `./runmyplayer.sh WHITE 60 localhost` |
| **Certificación local completa pre-entrega** | `.\scripts\verify_local.ps1` |
| **Certificación dentro de la VM (Bash)** | `./scripts/verify_vm.sh` |
| **Limpieza y purgado previo a exportar OVA** | `./scripts/clean_vm.sh` |
| **Generar manifiesto de entrega y hash SHA-256** | `.\scripts\generate_submission_manifest.ps1 -OvaPath <fichero.ova>` |

---

## 2. Compilación y Build

El motor está implementado en Java puro (Java 8+ compatible, testeado en Java 17/21) con dependencias exclusivas en el referee server y las librerías del starter kit oficial en [`TablutCompetition-master/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master).

### Windows (PowerShell)
```powershell
javac -cp "TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*" -d bin src\tablut\engine\*.java
```

### Linux / VirtualBox VM (Bash)
```bash
javac -cp "TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*" -d bin src/tablut/engine/*.java
```

> [!NOTE]
> Todos los `.class` se compilan en la carpeta `bin/`. La estructura de paquetes es `tablut.engine.*`.

---

## 3. Entrenamiento y Optimización de Pesos Heurísticos (SPSA)

El evaluador lineal utiliza 22 pesos posicionales y tácticos optimizados mediante **Simultaneous Perturbation Stochastic Approximation (SPSA)** sobre el simulador headless en RAM ([`HeadlessTournament.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/HeadlessTournament.java), sin coste de red ni GUI).

### 3.1 Pipeline de Entrenamiento Nocturno (`scripts/train_overnight.py`)
Diseñado para dejar ejecutando durante horas sin supervisión. Automatiza dos fases de optimización y captura señales de interrupción (`Ctrl+C`) de forma segura.

* **Fase 1 (Warm-Up & Targeted Acclimatization):**
  - **Profundidad de búsqueda:** Depth 3 + Quiescence.
  - **Partidas por paso:** 60 partidas con alternancia estricta de color.
  - **Parámetros:** Sub-bloque objetivo configurable (`--warmup-stage`, por defecto `positional` o `tactical_compound`).
  - **Duración:** 300 iteraciones (~12-15 minutos en multi-core).
  - **Objetivo:** Calibra la escala de nuevas heurísticas sin perturbar los parámetros macro ya convergidos.
* **Fase 2 (Global Covariance Fine-Tuning):**
  - **Profundidad de búsqueda:** Depth 4 + Quiescence.
  - **Partidas por paso:** 80 partidas (reduce la varianza del gradiente para 22 dimensiones).
  - **Parámetros:** Los 22 parámetros simultáneamente (`all`).
  - **Duración:** Continua indefinidamente (modo anytime).
  - **Objetivo:** Elimina sesgos de horizonte corto de Depth 3 y optimiza el acoplamiento cruzado de todas las 22 heurísticas.

#### Comandos recomendados:

```powershell
# 1. Modo nocturno completo: Fase 1 (calentamiento) -> Fase 2 (global Depth 4 en 22 dimensiones)
# Al pulsar Ctrl+C para finalizar, inyecta automáticamente los mejores pesos en Java y recompila
python scripts/train_overnight.py --auto-apply

# 2. Modo directo a Fase 2 global (los 22 parámetros simultáneos a Depth 4):
python scripts/train_overnight.py --skip-warmup --depth 4 --auto-apply

# 3. Calentamiento focalizado exclusivamente en las 5 nuevas heurísticas (Fase 12/13):
python scripts/train_overnight.py --warmup-stage tactical_compound --warmup-iters 200 --auto-apply

# 4. Forzar un número específico de hilos de CPU (por defecto usa todos los núcleos disponibles):
python scripts/train_overnight.py --threads 8 --auto-apply

# 5. Dry-run (comprueba la configuración sin arrancar partidas):
python scripts/train_overnight.py --dry-run
```

#### Parámetros disponibles (`train_overnight.py`):
* `--warmup-iters <N>`: Iteraciones para la Fase 1 (defecto: `300`).
* `--warmup-stage <STAGE>`: Sub-etapa para Fase 1 (`positional`, `tactical_compound`, `material`, `micro`).
* `--skip-warmup`: Salta la Fase 1 e inicia directamente en Fase 2 (los 22 parámetros).
* `--depth <D>`: Profundidad de búsqueda minimax en Fase 2 (defecto: `4`).
* `--games-phase2 <G>`: Partidas por paso SPSA en Fase 2 (defecto: `80`).
* `--threads <T>`: Hilos de trabajo en paralelo (defecto: todos los vCPUs lógicos).
* `--auto-apply`: Aplica automáticamente los mejores pesos a [`HeuristicWeights.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/HeuristicWeights.java) y recompila los binarios al terminar.

---

### 3.2 Optimizador SPSA Granular (`scripts/train_spsa.py`)
El motor de bajo nivel subyacente. Se utiliza para ejecuciones manuales con control total de hiperparámetros de optimización estocástica.

```powershell
# Ejecutar optimización manual a Depth 3 en todos los núcleos:
python scripts/train_spsa.py --depth 3 --games 60 --threads 8

# Entrenar únicamente parámetros posicionales:
python scripts/train_spsa.py --depth 3 --games 60 --params positional

# Reanudar optimización reseteando la tasa de aprendizaje decay (warm-restart):
python scripts/train_spsa.py --resume --warm-restart --depth 4 --games 80
```

#### Parámetros clave (`train_spsa.py`):
* `--depth <N>`: Profundidad de búsqueda en simulación (defecto: `3`).
* `--games <N>`: Partidas por paso de gradiente (defecto: `60`, siempre par para balance White/Black).
* `--threads <N>`: Hilos en paralelo.
* `--iters <N>`: Límite de iteraciones (defecto: `0`, infinito hasta interrupción).
* `--checkpoint <fichero>`: Fichero JSON atómico (defecto: `checkpoint_weights.json`).
* `--resume`: Carga el estado del checkpoint anterior.
* `--warm-restart`: Reinicia el decaimiento de la tasa de aprendizaje respecto a la sesión actual para evitar tasas congeladas tras miles de iteraciones pasadas.
* `--params <all|positional|material|micro|indices>`: Subconjunto de parámetros a perturbar.
* `--lr <a>`: Constante de paso de aprendizaje $a$ (defecto: `6.0`).
* `--pert <c>`: Amplitud de perturbación $c$ (defecto: `4.0`).

---

### 3.3 Inyector de Pesos a Java (`scripts/apply_weights.py`)
Lee el checkpoint generado ([`checkpoint_weights.json`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/checkpoint_weights.json)) e inyecta los valores optimizados directamente en el código fuente de [`HeuristicWeights.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/HeuristicWeights.java), recompilando el proyecto inmediatamente.

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

Ejecutan el servidor oficial ([`Server.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/Server.jar)), el agente DajeGeppetto y el oponente sobre sockets TCP en puertos 5800/5801.

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
Harness de torneo automatizado que enfrenta a DajeGeppetto contra los motores de referencia de la competición UNIBO disponibles en [`competitors/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/competitors) y [`team/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/team). Garantiza simetría de color (partidas pares alternando White/Black).

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

### 5.4 Match Inter-Versiones / Regresión de Código (`scripts/match_versions.ps1`)
Harness de benchmarking que extrae de Git una versión anterior (por defecto `HEAD~1` o cualquier commit/tag), la compila de forma aislada en `bin_prev/` y disputa un match a color alternado (1 White, 1 Black) contra la versión local actual usando el `Server.jar` oficial.

```powershell
# Match de 2 partidas a 15s por movimiento contra el commit anterior:
.\scripts\match_versions.ps1 -Games 2 -Timeout 15

# Comparar contra un commit específico con timeout oficial de 45s:
.\scripts\match_versions.ps1 -Commit a1b2c3d -Games 2 -Timeout 45

# Match con interfaz gráfica (GUI) activada:
.\scripts\match_versions.ps1 -Games 2 -Timeout 15 -EnableGui
```

#### Parámetros:
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

## 7. Minería de Aperturas y Análisis de Logs

### Analizador de Logs y Extractor de Aperturas (`scripts/mine_openings.py`)
Examina los logs del servidor de arbitraje en `logs/*_server.log`, reconstruye los movimientos efectuados en los primeros $N$ plies, y calcula los porcentajes de victoria de White y Black para identificar variantes de apertura ganadoras.

```powershell
# Analizar aperturas de los primeros 6 plies en todos los logs:
python scripts/mine_openings.py --logs-dir logs --max-plies 6

# Filtrar líneas con al menos 2 apariciones:
python scripts/mine_openings.py --logs-dir logs --max-plies 8 --min-frequency 2
```

---

## 8. Batería de Tests Unitarios y Verificación (Java)

Cada módulo del motor cuenta con tests unitarios y suites de regresión con método `main` ejecutable.

### Ejecución de tests en Windows (PowerShell)
```powershell
$cp = "bin;TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*"

# 1. Verificación de reglas oficiales Ashton Tablut (vs GameAshtonTablut oficial):
java -cp $cp tablut.engine.RuleVerificationTest

# 2. Test de rendimiento por estado y conteo de nodos (PerftTest, >85M NPS):
java -cp $cp tablut.engine.PerftTest

# 3. Test del motor de búsqueda Negamax / IDS / Time Management:
java -cp $cp tablut.engine.SearchEngineTest

# 4. Test del motor paralelo Lazy SMP (4 vCPUs):
java -cp $cp tablut.engine.ParallelSearchEngineTest

# 5. Test de la tabla de transposición lockless Hyatt XOR:
java -cp $cp tablut.engine.TranspositionTableTest

# 6. Test del libro de aperturas con simetría diedral D4:
java -cp $cp tablut.engine.OpeningBookTest

# 7. Test de Quiescence Search y extensiones tácticas:
java -cp $cp tablut.engine.QuiescenceSearchTest

# 8. Test de ordenación de movimientos (PV, Hash, Wins, Killers, History):
java -cp $cp tablut.engine.MoveOrdererTest

# 9. Test del evaluador heurístico lineal de 17 parámetros:
java -cp $cp tablut.engine.HeuristicEvaluationTest

# 10. Test de regresión de Fase 6 (GHI safety, Lazy ordering, Citadel geometry):
java -cp $cp tablut.engine.Phase6EnhancementsTest

# 11. Test de regresión de Fase 7 (PVS, Aspiration Windows, Pins):
java -cp $cp tablut.engine.Phase7OptimizationsTest

# 12. Test de regresión de Fase 9 (D4 expandido, corte mate temprano, gradiente enjambre):
java -cp $cp tablut.engine.Phase9OptimizationsTest

# 13. Test de regresión de Fase 12 (cortes analíticos O(1), cohesión de escudo, dispersión cuadrática, b_eff dinámico, extensiones singulares PVS):
java -cp $cp tablut.engine.Phase12OptimizationsTest

# 14. Test de regresión de Fase 13 (sanitización de apertura trono, SAFE_FORKS 2-pasos, anti-colgado raíz, TT bucket 2-slots 2^23):
java -cp $cp tablut.engine.Phase13RefinementsTest

# 15. Test de hardening táctico de Fase 14 (P1 trono, P2 LMR, P3 QS dist<=2, P4 camp corners, P5 aperturas epsilon-greedy):
java -cp $cp tablut.engine.Phase14TacticalHardeningTest
```

### 7.3 Auditoría de Ortogonalidad y Covarianza SVD (`scripts/analyze_feature_covariance.py`)
Herramienta de análisis matemático de los vectores de características en partidas simuladas en RAM:
```powershell
# Simular 1.000 estados y generar informe de correlación Pearson y espectro SVD:
python scripts/analyze_feature_covariance.py --states 1000 --generate

# Analizar un CSV existente con umbral de correlación personalizado:
python scripts/analyze_feature_covariance.py --csv feature_matrix.csv --threshold 0.75
```

### Ejecución de tests en Linux / macOS (Bash)
```bash
CP="bin:TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*"

# Ejemplo:
java -cp "$CP" tablut.engine.RuleVerificationTest
java -cp "$CP" tablut.engine.Phase12OptimizationsTest
```

---

## 8. Certificación y Despliegue en la Máquina Virtual (Pre-Submission)

Scripts para validar los **10 invariantes oficiales** de la competición antes de exportar el appliance `.ova`.

### 8.1 Certificación Local en Windows (`scripts/verify_local.ps1`)
Ejecuta la pipeline de compilación limpia, chequeo de immutabilidad de ficheros del starter, conversión de finales de línea a POSIX LF, y ejecución de la batería de tests unitarios:
```powershell
# Certificación completa con partida rápida contra RandomPlayer:
.\scripts\verify_local.ps1

# Certificación rápida (omitiendo la partida smoke):
.\scripts\verify_local.ps1 -SkipMatch
```

### 8.2 Certificación dentro de la VM Debian (`scripts/verify_vm.sh`)
Ejecutar dentro de la máquina virtual oficial en `/home/tablut/tablut`:
```bash
cd /home/tablut/tablut
chmod +x scripts/verify_vm.sh
./scripts/verify_vm.sh
```

### 8.3 Limpieza y Purgado Previo a la Exportación (`scripts/clean_vm.sh`)
Ejecutar dentro de la VM justo antes de apagarla para exportar el `.ova`. Recompila `bin/`, borra logs, scratch, caché de apt y vacía el historial de bash:
```bash
cd /home/tablut/tablut
chmod +x scripts/clean_vm.sh
./scripts/clean_vm.sh
```

### 8.4 Generador de Manifiesto y Checksum SHA-256 (`scripts/generate_submission_manifest.ps1`)
Ejecutar en la máquina anfitriona tras exportar el fichero OVA desde VirtualBox. Calcula el hash SHA-256, el tamaño del fichero y formatea el archivo `submission_manifest.txt` listo para subir a Virtuale:
```powershell
.\scripts\generate_submission_manifest.ps1 -OvaPath "Tablut_DajeGeppetto_Submission.ova" -OneDriveUrl "https://liveunibo-my.sharepoint.com/:u:/g/personal/..."
```

