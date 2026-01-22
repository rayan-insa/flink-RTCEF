"""
Integration tests for Synchronous Optimization Protocol.

Tests the full protocol: optimizer, sync commands, and command builders.
"""
import json
import pytest
from unittest.mock import Mock, MagicMock, call
from controller_job.optimizer import HyperparameterOptimizer
from controller_job.sync import create_pause_command, create_play_command
from controller_job.models.instruction import Instruction, InstructionType


class TestSyncCommands:
    """Tests for pause/play sync commands."""

    def test_pause_command_format(self):
        """Pause command has correct structure."""
        cmd = create_pause_command(timestamp=1700000000)
        parsed = json.loads(cmd)
        
        assert parsed["type"] == "pause"
        assert parsed["timestamp"] == 1700000000
        assert parsed["model_id"] == -1

    def test_play_command_format(self):
        """Play command has correct structure with model_id."""
        cmd = create_play_command(timestamp=1700000001, model_id=42)
        parsed = json.loads(cmd)
        
        assert parsed["type"] == "play"
        assert parsed["timestamp"] == 1700000001
        assert parsed["model_id"] == 42


class TestCommandBuilders:
    """Tests for Factory command builders."""

    def test_train_command(self):
        """Train command has correct structure."""
        opt = HyperparameterOptimizer()
        cmd = opt.build_train_command(
            timestamp=1700000000,
            params={"pMin": 0.05, "gamma": 0.002}
        )
        
        assert cmd["type"] == "train"
        assert cmd["timestamp"] == 1700000000
        assert "id" in cmd
        assert cmd["train_id"] == 0

    def test_opt_initialise_command(self):
        """Opt initialise command has correct structure."""
        opt = HyperparameterOptimizer()
        cmd = opt.build_opt_initialise_command(timestamp=1700000000)
        
        assert cmd["type"] == "opt_initialise"
        assert cmd["optimisation_id"] == 0

    def test_opt_step_command(self):
        """Opt step command has correct structure with params list."""
        opt = HyperparameterOptimizer()
        cmd = opt.build_opt_step_command(
            timestamp=1700000000,
            params=[0.05, 0.002]
        )
        
        assert cmd["type"] == "opt_step"
        params = json.loads(cmd["params"])
        assert params["params"] == [0.05, 0.002]

    def test_opt_finalise_command(self):
        """Opt finalise command has best_i and increments counter."""
        opt = HyperparameterOptimizer()
        assert opt.optimize_counter == 0
        
        cmd = opt.build_opt_finalise_command(timestamp=1700000000, best_i=3)
        
        assert cmd["type"] == "opt_finalise"
        assert cmd["best_i"] == 3
        assert opt.optimize_counter == 1  # Incremented after finalise


class TestSyncOptimizationLoop:
    """Tests for the full synchronous optimization loop."""

    def test_run_optimization_full_loop(self):
        """Full optimization loop with mocked callbacks."""
        opt = HyperparameterOptimizer(n_initial_points=2, n_total_evals=3, seed=42)
        
        commands_sent = []
        sync_sent = []
        report_queue = [
            # 3 opt_step reports
            {"reply_id": "cmd-1", "model_id": 0, "metrics": json.dumps({"f_val": -0.5})},
            {"reply_id": "cmd-2", "model_id": 1, "metrics": json.dumps({"f_val": -0.7})},
            {"reply_id": "cmd-3", "model_id": 2, "metrics": json.dumps({"f_val": -0.6})},
            # opt_finalise report
            {"reply_id": "cmd-4", "model_id": 3, "params_dict": json.dumps({"pMin": 0.05, "gamma": 0.002})}
        ]
        
        def mock_send_command(cmd):
            commands_sent.append(cmd)
        
        def mock_wait_for_report():
            return report_queue.pop(0)
        
        def mock_send_sync(sync_msg):
            sync_sent.append(sync_msg)
        
        # Run optimization
        final_model_id = opt.run_optimization(
            timestamp=1700000000,
            send_command=mock_send_command,
            wait_for_report=mock_wait_for_report,
            send_sync=mock_send_sync
        )
        
        # Verify sync commands
        assert len(sync_sent) == 2  # pause + play
        assert "pause" in sync_sent[0]
        assert "play" in sync_sent[1]
        
        # Verify commands sent
        assert len(commands_sent) == 5  # initialise + 3 steps + finalise
        assert commands_sent[0]["type"] == "opt_initialise"
        assert commands_sent[1]["type"] == "opt_step"
        assert commands_sent[2]["type"] == "opt_step"
        assert commands_sent[3]["type"] == "opt_step"
        assert commands_sent[4]["type"] == "opt_finalise"
        
        # Verify best_i (iteration with lowest f_val = -0.7)
        assert commands_sent[4]["best_i"] == 1
        
        # Verify final model ID
        assert final_model_id == 3


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
