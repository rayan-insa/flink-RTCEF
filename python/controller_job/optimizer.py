"""
Synchronous Hyperparameter Optimizer - Baseline-compatible implementation

This optimizer implements the synchronous multi-step optimization protocol:
1. Pause engine
2. Send opt_initialise to Factory
3. Loop n_total_evals times:
   - ASK: get next params from Bayesian model
   - Send opt_step to Factory
   - WAIT: block until Factory report arrives
   - TELL: update Bayesian model with result
4. Send opt_finalise(best_i) to Factory
5. Play engine with new model

Based on: external/rtcef/services/optimiser.py and libraries/optimisers/skopt_wrapper.py
"""
import json
import logging
import time
from math import inf
from typing import Optional, Callable, List, Dict, Any

from skopt import Optimizer
from skopt.space import Real

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.sync import create_pause_command, create_play_command


class HyperparameterOptimizer:
    """
    Synchronous Optimizer using Scikit-Optimize (skopt) Ask-Tell pattern.
    
    Implements the full optimization protocol with blocking waits for Factory reports.
    """

    # Default search space for Wayeb hyperparameters
    DEFAULT_SPACE = [
        Real(0.001, 0.1, name='pMin'),    # Minimum probability threshold
        Real(0.0, 0.005, name='gamma')     # Smoothing parameter
    ]

    def __init__(
        self,
        n_initial_points: int = 5,
        n_total_evals: int = 10,
        seed: int = 42,
        acq_func: str = 'EI'
    ):
        self.logger = logging.getLogger(__name__)
        self.n_initial_points = n_initial_points
        self.n_total_evals = n_total_evals
        self.seed = seed
        self.acq_func = acq_func
        
        # Command tracking
        self.command_counter = 0
        self.train_counter = 0
        self.optimize_counter = 0
        
        # Optimizer state
        self.opt: Optional[Optimizer] = None
        self.current_best_params: Dict[str, float] = {'pMin': 0.05, 'gamma': 0.001}
        
        # Optimization session state
        self.opt_model_params: List[List[float]] = []
        self.best_i: int = 0
        self.best_obj: float = inf
        
        self._initialize_optimizer()

    def _initialize_optimizer(self):
        """Initialize or reset the skopt Optimizer."""
        self.opt = Optimizer(
            dimensions=self.DEFAULT_SPACE,
            base_estimator='GP',
            n_initial_points=self.n_initial_points,
            random_state=self.seed,
            acq_func=self.acq_func
        )
        self.logger.info(
            f"Initialized skopt Optimizer "
            f"(n_initial={self.n_initial_points}, n_total={self.n_total_evals}, seed={self.seed})"
        )

    def _get_command_id(self) -> str:
        """Generate unique command ID."""
        cid = self.command_counter
        self.command_counter += 1
        return f"cmd-{cid}"

    # =========================================================================
    # Command Builders
    # =========================================================================

    def build_train_command(self, timestamp: int, params: Dict[str, float]) -> Dict:
        """Build a simple TRAIN command (for retrain instruction)."""
        self.train_counter += 1
        return {
            "type": "train",
            "id": self._get_command_id(),
            "train_id": self.train_counter - 1,
            "optimisation_id": -1,
            "timestamp": timestamp,
            "params": json.dumps({"params": params}),
            "best_i": -1
        }

    def build_opt_initialise_command(self, timestamp: int) -> Dict:
        """Build opt_initialise command to start optimization."""
        return {
            "type": "opt_initialise",
            "id": self._get_command_id(),
            "train_id": -1,
            "optimisation_id": self.optimize_counter,
            "timestamp": timestamp,
            "params": json.dumps({"params": None}),
            "best_i": -1
        }

    def build_opt_step_command(self, timestamp: int, params: List[float]) -> Dict:
        """Build opt_step command for one optimization iteration."""
        return {
            "type": "opt_step",
            "id": self._get_command_id(),
            "train_id": -1,
            "optimisation_id": self.optimize_counter,
            "timestamp": timestamp,
            "params": json.dumps({"params": params}),
            "best_i": -1
        }

    def build_opt_finalise_command(self, timestamp: int, best_i: int) -> Dict:
        """Build opt_finalise command to select and deploy best model."""
        cmd = {
            "type": "opt_finalise",
            "id": self._get_command_id(),
            "train_id": -1,
            "optimisation_id": self.optimize_counter,
            "timestamp": timestamp,
            "params": json.dumps({"params": None}),
            "best_i": best_i
        }
        self.optimize_counter += 1  # Increment after finalise
        return cmd

    # =========================================================================
    # Synchronous Optimization Loop
    # =========================================================================

    def run_optimization(
        self,
        timestamp: int,
        send_command: Callable[[Dict], None],
        wait_for_report: Callable[[], Dict],
        send_sync: Callable[[str], None]
    ) -> int:
        """
        Run the full synchronous optimization loop.
        
        Args:
            timestamp: Starting timestamp for the optimization
            send_command: Callback to send command to Factory (via Kafka)
            wait_for_report: Callback that blocks until Factory report arrives
            send_sync: Callback to send sync command to Engine (pause/play)
            
        Returns:
            model_id of the final optimized model
        """
        self.logger.info(f"=== Starting Optimization Loop (n_evals={self.n_total_evals}) ===")
        
        # Reset session state
        self.opt_model_params = []
        self.best_i = 0
        self.best_obj = inf
        self._initialize_optimizer()
        
        # 1. Pause Engine
        self.logger.info("Step 1: Pausing inference engine...")
        send_sync(create_pause_command(timestamp))
        
        # 2. Send opt_initialise
        self.logger.info("Step 2: Sending opt_initialise to Factory...")
        init_cmd = self.build_opt_initialise_command(timestamp)
        send_command(init_cmd)
        
        # 3. Optimization Loop
        self.logger.info(f"Step 3: Running {self.n_total_evals} optimization iterations...")
        
        for i in range(self.n_total_evals):
            # ASK: Get next params from Bayesian model
            next_params = self.opt.ask()
            pMin, gamma = next_params
            self.opt_model_params.append(next_params)
            
            self.logger.info(f"  Iteration {i}/{self.n_total_evals}: ASK -> pMin={pMin:.6f}, gamma={gamma:.6f}")
            
            # Send opt_step command
            step_cmd = self.build_opt_step_command(timestamp, next_params)
            send_command(step_cmd)
            
            # WAIT: Block until Factory report arrives
            self.logger.info(f"  Waiting for Factory report...")
            report = wait_for_report()
            
            # Extract metrics from report
            metrics_str = report.get('metrics', '{}')
            if isinstance(metrics_str, str):
                metrics = json.loads(metrics_str)
            else:
                metrics = metrics_str
            
            f_val = metrics.get('f_val', 0.0)
            
            # TELL: Update Bayesian model
            self.opt.tell(next_params, f_val)
            self.logger.info(f"  TELL -> f_val={f_val:.4f}")
            
            # Track best
            if f_val < self.best_obj:
                self.best_i = i
                self.best_obj = f_val
                self.logger.info(f"  New best! iteration={i}, f_val={f_val:.4f}")
        
        # 4. Send opt_finalise with best_i
        self.logger.info(f"Step 4: Sending opt_finalise (best_i={self.best_i}, best_obj={self.best_obj:.4f})...")
        finalise_cmd = self.build_opt_finalise_command(timestamp, self.best_i)
        send_command(finalise_cmd)
        
        # Wait for final report with model_id
        final_report = wait_for_report()
        model_id = final_report.get('model_id', 0)
        
        # Update current best params
        params_str = final_report.get('params_dict', '{}')
        if isinstance(params_str, str) and params_str:
            self.current_best_params = json.loads(params_str)
        
        # 5. Play Engine with new model
        self.logger.info(f"Step 5: Resuming inference engine with model_id={model_id}...")
        send_sync(create_play_command(timestamp, model_id))
        
        self.logger.info(f"=== Optimization Complete! Final model_id={model_id} ===")
        return model_id

    # =========================================================================
    # Simple Train (for RETRAIN instruction)
    # =========================================================================

    def run_train(
        self,
        timestamp: int,
        params: Optional[Dict[str, float]],
        send_command: Callable[[Dict], None],
        wait_for_report: Callable[[], Dict],
        send_sync: Callable[[str], None]
    ) -> int:
        """
        Run a simple train command (not optimization).
        
        Args:
            timestamp: Timestamp for the train
            params: Parameters to use (or None for current_best_params)
            send_command: Callback to send command to Factory
            wait_for_report: Callback that blocks until Factory report arrives
            send_sync: Callback to send sync command to Engine
            
        Returns:
            model_id of the newly trained model
        """
        self.logger.info("=== Running Simple Train ===")
        
        # Use provided params or defaults
        train_params = params or self.current_best_params
        
        # Pause Engine
        send_sync(create_pause_command(timestamp))
        
        # Send train command
        train_cmd = self.build_train_command(timestamp, train_params)
        send_command(train_cmd)
        
        # Wait for report
        report = wait_for_report()
        model_id = report.get('model_id', 0)
        
        # Play Engine with new model
        send_sync(create_play_command(timestamp, model_id))
        
        self.logger.info(f"=== Train Complete! model_id={model_id} ===")
        return model_id

    # =========================================================================
    # Legacy API (for backward compatibility with event-driven code)
    # =========================================================================

    def create_command(self, instruction: Instruction) -> Optional[Dict]:
        """
        Legacy API: Create a single command from instruction.
        
        Note: This does NOT run the full optimization loop.
        For full optimization, use run_optimization().
        """
        if instruction.instruction_type == InstructionType.RETRAIN:
            params = {
                'pMin': instruction.parameters.get('pMin', 0.05),
                'gamma': instruction.parameters.get('gamma', 0.001)
            }
            return self.build_train_command(instruction.timestamp, params)
        
        elif instruction.instruction_type == InstructionType.OPTIMIZE:
            # Return opt_initialise - caller must handle the loop
            self.logger.warning(
                "create_command() called for OPTIMIZE. "
                "Consider using run_optimization() for full sync loop."
            )
            return self.build_opt_initialise_command(instruction.timestamp)
        
        elif instruction.instruction_type == InstructionType.UPDATE_PARAMS:
            # Not a Factory command - just update local state
            self.current_best_params.update(instruction.parameters)
            return None
        
        return None

    def update_model(self, report: Dict):
        """
        Legacy API: Update optimizer state from Factory report.
        
        Note: In sync mode, this is handled inside run_optimization().
        """
        status = report.get('status', 'success')
        if status == 'success':
            metrics = report.get('metrics', {})
            if isinstance(metrics, str):
                metrics = json.loads(metrics)
            
            f_val = metrics.get('f_val')
            if f_val is not None:
                self.logger.info(f"Legacy update_model: f_val={f_val}")
