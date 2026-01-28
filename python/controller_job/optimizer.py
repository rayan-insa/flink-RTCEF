"""
Hyperparameter Optimizer - Flink-Native State Machine Compatible

This optimizer can be used in two modes:
1. **Synchronous Mode**: run_optimization() for blocking loops (standalone scripts)
2. **Event-Driven Mode**: Step-by-step methods for Flink state machine:
   - init_session(): Start a new optimization session
   - ask(): Get next params to try
   - tell(f_val): Update Bayesian model with result
   - get_best_i(): Get best iteration index
   - get_state_snapshot(): Serialize optimizer state for Flink ValueState
   - load_state_snapshot(): Restore optimizer state from serialized data

Based on: external/rtcef/services/optimiser.py and libraries/optimisers/skopt_wrapper.py
"""
import json
import logging
import pickle
import base64
from dataclasses import dataclass, field, asdict
from enum import Enum
from math import inf
from typing import Optional, Callable, List, Dict, Any, Tuple

from skopt import Optimizer
from skopt.space import Real

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.sync import create_pause_command, create_play_command


class OptimizationPhase(Enum):
    """State machine phases for optimization."""
    IDLE = "IDLE"
    INITIALIZING = "INITIALIZING"
    WAITING_FOR_REPORT = "WAITING_FOR_REPORT"
    OPTIMIZING = "OPTIMIZING"
    FINALIZING = "FINALIZING"


@dataclass
class OptimizationSession:
    """
    Serializable snapshot of an optimization session.
    
    This dataclass captures all state needed to resume an optimization
    after Flink restarts or between event arrivals.
    """
    phase: str = OptimizationPhase.IDLE.value
    current_iteration: int = 0
    n_total_evals: int = 10
    opt_model_params: List[List[float]] = field(default_factory=list)
    best_i: int = 0
    best_obj: float = inf
    optimizer_pickle: str = ""  # Base64-encoded pickled skopt.Optimizer
    timestamp: int = 0
    optimize_counter: int = 0
    
    def to_json(self) -> str:
        """Serialize to JSON string."""
        return json.dumps(asdict(self))
    
    @classmethod
    def from_json(cls, json_str: str) -> 'OptimizationSession':
        """Deserialize from JSON string."""
        data = json.loads(json_str)
        return cls(**data)


class HyperparameterOptimizer:
    """
    Stateless Optimizer using Scikit-Optimize (skopt) Ask-Tell pattern.
    
    Supports both synchronous (blocking) and event-driven (Flink state machine) modes.
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
        
        # Optimizer state (transient - will be serialized via session)
        self.opt: Optional[Optimizer] = None
        self.current_best_params: Dict[str, float] = {'pMin': 0.05, 'gamma': 0.001}
        
        # Session state (will be persisted in Flink ValueState)
        self.session: Optional[OptimizationSession] = None
        
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
    # State Serialization (for Flink ValueState)
    # =========================================================================

    def _serialize_optimizer(self) -> str:
        """Pickle and base64-encode the skopt Optimizer."""
        if self.opt is None:
            return ""
        pickled = pickle.dumps(self.opt)
        return base64.b64encode(pickled).decode('utf-8')
    
    def _deserialize_optimizer(self, encoded: str) -> Optional[Optimizer]:
        """Restore skopt Optimizer from base64-encoded pickle."""
        if not encoded:
            return None
        pickled = base64.b64decode(encoded.encode('utf-8'))
        return pickle.loads(pickled)

    def get_state_snapshot(self) -> str:
        """
        Get JSON-serializable snapshot of the current optimization session.
        
        Use this to persist state in Flink's ValueState between events.
        """
        if self.session is None:
            return ""
        # Update session with current optimizer state
        self.session.optimizer_pickle = self._serialize_optimizer()
        return self.session.to_json()
    
    def load_state_snapshot(self, snapshot_json: str) -> bool:
        """
        Restore optimization session from a JSON snapshot.
        
        Returns True if session was restored, False if no session to restore.
        """
        if not snapshot_json:
            self.session = None
            return False
        
        self.session = OptimizationSession.from_json(snapshot_json)
        
        # Restore the skopt Optimizer
        if self.session.optimizer_pickle:
            self.opt = self._deserialize_optimizer(self.session.optimizer_pickle)
        
        self.optimize_counter = self.session.optimize_counter
        self.logger.info(
            f"Restored session: phase={self.session.phase}, "
            f"iteration={self.session.current_iteration}/{self.session.n_total_evals}"
        )
        return True

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
    # Event-Driven API (for Flink State Machine)
    # =========================================================================

    def init_session(self, timestamp: int) -> Tuple[Dict, str]:
        """
        Start a new optimization session.
        
        Call this when receiving an OPTIMIZE instruction.
        
        Returns:
            Tuple of (opt_initialise command, pause_sync_command)
        """
        self._initialize_optimizer()
        
        self.session = OptimizationSession(
            phase=OptimizationPhase.INITIALIZING.value,
            current_iteration=0,
            n_total_evals=self.n_total_evals,
            opt_model_params=[],
            best_i=0,
            best_obj=inf,
            optimizer_pickle="",
            timestamp=timestamp,
            optimize_counter=self.optimize_counter
        )
        
        init_cmd = self.build_opt_initialise_command(timestamp)
        pause_cmd = create_pause_command(timestamp)
        
        # Transition to WAITING_FOR_REPORT
        self.session.phase = OptimizationPhase.WAITING_FOR_REPORT.value
        
        self.logger.info(f"init_session: Starting optimization at ts={timestamp}")
        return init_cmd, pause_cmd

    def ask(self) -> Tuple[List[float], Dict]:
        """
        ASK: Get next hyperparameters to try.
        
        Call this after receiving a Factory report during optimization.
        
        Returns:
            Tuple of (params [pMin, gamma], opt_step command)
        """
        if self.session is None:
            raise RuntimeError("No active optimization session. Call init_session() first.")
        
        if self.opt is None:
            raise RuntimeError("Optimizer not initialized")
        
        next_params = self.opt.ask()
        pMin, gamma = next_params
        
        self.session.opt_model_params.append(next_params)
        
        step_cmd = self.build_opt_step_command(self.session.timestamp, next_params)
        
        # Update session state
        self.session.phase = OptimizationPhase.WAITING_FOR_REPORT.value
        
        self.logger.info(
            f"ask: Iteration {self.session.current_iteration} -> "
            f"pMin={pMin:.6f}, gamma={gamma:.6f}"
        )
        return next_params, step_cmd

    def tell(self, f_val: float) -> bool:
        """
        TELL: Update Bayesian model with the result of the last iteration.
        
        Call this after receiving a Factory report with metrics.
        
        Args:
            f_val: The objective function value (loss) from the Factory
            
        Returns:
            True if optimization should continue, False if all iterations done
        """
        if self.session is None:
            raise RuntimeError("No active optimization session")
        
        if self.opt is None:
            raise RuntimeError("Optimizer not initialized")
        
        # Get the last params we asked for
        if not self.session.opt_model_params:
            raise RuntimeError("No params recorded - call ask() first")
        
        last_params = self.session.opt_model_params[-1]
        
        # Update Bayesian model
        self.opt.tell(last_params, f_val)
        
        # Track best
        if f_val < self.session.best_obj:
            self.session.best_i = self.session.current_iteration
            self.session.best_obj = f_val
            self.logger.info(
                f"tell: New best! iteration={self.session.current_iteration}, "
                f"f_val={f_val:.4f}"
            )
        
        # Advance iteration
        self.session.current_iteration += 1
        
        # Check if we should continue
        should_continue = self.session.current_iteration < self.session.n_total_evals
        
        if should_continue:
            self.session.phase = OptimizationPhase.OPTIMIZING.value
        else:
            self.session.phase = OptimizationPhase.FINALIZING.value
        
        self.logger.info(
            f"tell: f_val={f_val:.4f}, iteration {self.session.current_iteration}/"
            f"{self.session.n_total_evals}, continue={should_continue}"
        )
        return should_continue

    def finalize(self) -> Tuple[Dict, int]:
        """
        Finalize the optimization session.
        
        Call this when tell() returns False (all iterations complete).
        
        Returns:
            Tuple of (opt_finalise command, best_i)
        """
        if self.session is None:
            raise RuntimeError("No active optimization session")
        
        best_i = self.session.best_i
        finalise_cmd = self.build_opt_finalise_command(self.session.timestamp, best_i)
        
        self.logger.info(
            f"finalize: best_i={best_i}, best_obj={self.session.best_obj:.4f}"
        )
        return finalise_cmd, best_i

    def complete_session(self, model_id: int) -> str:
        """
        Complete the optimization session after receiving final Factory report.
        
        Returns:
            play_sync_command
        """
        if self.session is None:
            raise RuntimeError("No active optimization session")
        
        play_cmd = create_play_command(self.session.timestamp, model_id)
        
        self.logger.info(f"complete_session: Resuming with model_id={model_id}")
        
        # Clear session
        self.session = None
        
        return play_cmd

    def get_phase(self) -> OptimizationPhase:
        """Get current optimization phase."""
        if self.session is None:
            return OptimizationPhase.IDLE
        return OptimizationPhase(self.session.phase)

    def get_best_i(self) -> int:
        """
        Get the iteration index of the best result found so far.
        
        Returns:
            int: The index of the best iteration.
        """
        if self.session is None:
            return 0
        return self.session.best_i
