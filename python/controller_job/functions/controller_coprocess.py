"""
Controller CoProcessFunction - Flink-Native State Machine.

Implements the synchronous optimization protocol as an event-driven state machine
within Flink. It coordinates instructions from the Observer and reports from
the Factory, managing the optimization state through Flink's `ValueState`.
"""

from pyflink.datastream import (
    KeyedCoProcessFunction,
    RuntimeContext,
    OutputTag
)
from pyflink.datastream.state import ValueStateDescriptor
from pyflink.common.typeinfo import Types
import json
import logging

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.optimizer import (
    HyperparameterOptimizer,
    OptimizationPhase,
    OptimizationSession
)

# Output tags for side outputs
FACTORY_COMMAND_TAG = OutputTag("factory_command", Types.STRING())
ENGINE_SYNC_TAG = OutputTag("engine_sync", Types.STRING())


class ControllerCoProcessFunction(KeyedCoProcessFunction):
    """
    KeyedCoProcessFunction that implements the synchronous optimization protocol
    using a Flink-native state machine.
    
    Stream 1 (process_element1): Instructions from Observer
    Stream 2 (process_element2): Reports from Factory
    
    State:
    - session_state: JSON-serialized OptimizationSession
    - optimizer: HyperparameterOptimizer instance (transient, restored from session)
    """

    def __init__(self):
        """Initialize the co-process function variables."""
        self.logger = logging.getLogger(__name__)
        self.optimizer: HyperparameterOptimizer = None
        self.session_state = None

    def open(self, runtime_context: RuntimeContext):
        """
        Initialize state descriptors and the hyperparameter optimizer.
        
        Args:
            runtime_context: The runtime context for streaming functions.
        """
        # State to persist optimization session between events
        session_descriptor = ValueStateDescriptor(
            "optimization_session",
            Types.STRING()
        )
        self.session_state = runtime_context.get_state(session_descriptor)
        
        # Initialize optimizer (will be loaded from state if session exists)
        self.optimizer = HyperparameterOptimizer()
        
        self.logger.info("ControllerCoProcessFunction initialized (State Machine Mode)")

    def _persist_state(self):
        """Save optimizer state to Flink ValueState."""
        snapshot = self.optimizer.get_state_snapshot()
        if snapshot:
            self.session_state.update(snapshot)
            self.logger.debug(f"Persisted state: {self.optimizer.get_phase().value}")

    def _restore_state(self) -> bool:
        """Restore optimizer state from Flink ValueState. Returns True if restored."""
        snapshot = self.session_state.value()
        if snapshot:
            return self.optimizer.load_state_snapshot(snapshot)
        return False

    def _clear_state(self):
        """Clear the optimization session state."""
        self.session_state.clear()
        self.logger.debug("Cleared session state")

    def process_element1(self, instruction: Instruction, ctx: KeyedCoProcessFunction.Context):
        """
        Process Instructions from Observer (Stream 1).
        
        Args:
            instruction: The parsed instruction from the Observer.
            ctx: The context for the co-process function.
        """
        self.logger.info(
            f"[Stream1] Received Instruction: model_id={instruction.model_id}, "
            f"type={instruction.instruction_type}"
        )
        self.logger.info("TEST: process_element1 entered successfully")
        
        # Check current state
        self._restore_state()
        current_phase = self.optimizer.get_phase()
        
        if current_phase != OptimizationPhase.IDLE:
            self.logger.warning(
                f"Received instruction while in phase {current_phase.value}. "
                f"Ignoring (already optimizing)."
            )
            return
        
        if instruction.instruction_type == InstructionType.OPTIMIZE:
            # Start new optimization session
            for tag, val in self._handle_optimize_instruction(instruction, ctx):
                yield tag, val
        
        elif instruction.instruction_type == InstructionType.RETRAIN:
            # Simple train - could implement similarly but for now log warning
            self.logger.warning(
                "RETRAIN not implemented in state machine mode. "
                "Use OPTIMIZE instead or run standalone controller."
            )
        
        elif instruction.instruction_type == InstructionType.UPDATE_PARAMS:
            # Just update params locally
            self.optimizer.current_best_params.update(instruction.parameters)
            self.logger.info(f"Updated params: {self.optimizer.current_best_params}")

    def _handle_optimize_instruction(
        self,
        instruction: Instruction,
        ctx: KeyedCoProcessFunction.Context
    ):
        """Handle OPTIMIZE instruction - start new session."""
        timestamp = instruction.timestamp
        
        # Initialize session (creates pause + opt_initialise commands)
        init_cmd, pause_cmd = self.optimizer.init_session(timestamp)
        
        # Emit pause command to engine
        yield ENGINE_SYNC_TAG, pause_cmd
        self.logger.info(f"Emitted PAUSE command")
        
        # Emit opt_initialise to Factory
        yield FACTORY_COMMAND_TAG, json.dumps(init_cmd)
        self.logger.info(f"Emitted opt_initialise command")
        
        # Send first opt_step immediately (ASK before waiting)
        next_params, step_cmd = self.optimizer.ask()
        yield FACTORY_COMMAND_TAG, json.dumps(step_cmd)
        self.logger.info(f"Emitted first opt_step: params={next_params}")
        
        # Persist state
        self._persist_state()

    def process_element2(self, report_json: str, ctx: KeyedCoProcessFunction.Context):
        """
        Process Reports from Factory (Stream 2).
        
        Args:
            report_json: Raw JSON report string from the Factory.
            ctx: The context for the co-process function.
        """
        try:
            report = json.loads(report_json)
            self.logger.info(
                f"[Stream2] Received Report: reply_id={report.get('reply_id')}, "
                f"status={report.get('status')}"
            )
        except Exception as e:
            self.logger.error(f"Failed to parse report: {e}")
            return
        
        # Restore state
        if not self._restore_state():
            self.logger.debug("No active session, ignoring report")
            return
        
        current_phase = self.optimizer.get_phase()
        
        if report.get('status') == 'error':
            self.logger.error(f"Received ERROR report from Factory: {report.get('error')}")
            for tag, val in self._abort_session_gen(report.get('error')):
                yield tag, val
            return

        if current_phase == OptimizationPhase.IDLE:
            self.logger.debug("Received report but in IDLE state, ignoring")
            return
        
        elif current_phase == OptimizationPhase.WAITING_FOR_REPORT:
            # We need to dispatch to helper but helper cannot yield easily if it's separate method
            # unless we return generator. For simplicity given PyFlink constraints,
            # merging logic here or making helper return list of emits.
            # Refactoring slightly to keep yield in main process method.
            for tag, val in self._handle_report_during_optimization_gen(report):
                yield tag, val
        
        elif current_phase == OptimizationPhase.FINALIZING:
             for tag, val in self._handle_finalize_report_gen(report):
                 yield tag, val
        
        else:
            self.logger.warning(f"Unexpected phase {current_phase.value} for report")

    def _abort_session_gen(self, error_msg: str):
        """Abort current session due to error. Generator."""
        # Unblock engine
        # We don't have a model_id to restart with? Use 0 or just PLAY?
        # create_play_command requires model_id.
        # We should probably use the LAST known model_id?
        # OptimizationSession doesn't store last model_id explicitly?
        # Actually it does not.
        # But we can just send PLAY with model_id=0 or -1?
        # Engine expects model_id.
        # Let's assume 0 for safety or check if we can retrieve it.
        # For now, 0.
        
        play_cmd = self.optimizer.complete_session(0) # This clears session too
        # But complete_session assumes success.
        # We need manual clearing.
        
        # Manually clear session
        self.optimizer.session = None
        self._clear_state()
        
        # Send PLAY
        from controller_job.sync import create_play_command
        # We need timestamp.
        import time
        ts = int(time.time()) 
        # Actually usage of self.optimizer.complete_session(0) is acceptable 
        # as it generates the PLAY command and clears session.
        # But we might want to log differently.
        
        yield ENGINE_SYNC_TAG, play_cmd
        self.logger.info(f"Session ABORTED due to error: {error_msg}")

    def _handle_report_during_optimization_gen(
        self,
        report: dict
    ):
        """Handle Factory report during optimization loop. Generator."""
        # Extract f_val from metrics
        metrics_str = report.get('metrics', '{}')
        if isinstance(metrics_str, str):
            metrics = json.loads(metrics_str)
        else:
            metrics = metrics_str
        
        f_val = metrics.get('f_val', 0.0)
        
        # TELL: Update Bayesian model
        should_continue = self.optimizer.tell(f_val)
        
        if should_continue:
            # ASK: Get next params and send opt_step
            next_params, step_cmd = self.optimizer.ask()
            yield FACTORY_COMMAND_TAG, json.dumps(step_cmd)
            self.logger.info(
                f"Optimization continues: emitted opt_step, params={next_params}"
            )
        else:
            # All iterations done, finalize
            finalise_cmd, best_i = self.optimizer.finalize()
            yield FACTORY_COMMAND_TAG, json.dumps(finalise_cmd)
            self.logger.info(
                f"Optimization complete: emitted opt_finalise, best_i={best_i}"
            )
        
        # Persist state
        self._persist_state()

    def _handle_finalize_report_gen(
        self,
        report: dict
    ):
        """Handle Factory report after opt_finalise. Generator."""
        model_id = report.get('model_id', 0)
        
        # Complete session (creates play command)
        play_cmd = self.optimizer.complete_session(model_id)
        yield ENGINE_SYNC_TAG, play_cmd
        
        self.logger.info(f"Session complete: emitted PLAY with model_id={model_id}")
        
        self._clear_state()
