"""
Engine Sync Helper - Pause/Play mechanism for WayebEngine

Sends synchronization commands to the `enginesync` Kafka topic to pause/play
the inference engine during optimization procedures.
"""
import json
import logging
from typing import Optional

logger = logging.getLogger(__name__)


def create_pause_command(timestamp: int) -> str:
    """
    Create a pause command for the inference engine.
    
    Args:
        timestamp: Unix timestamp of the pause request
        
    Returns:
        JSON string for pause command
    """
    command = {
        "type": "pause",
        "timestamp": timestamp,
        "model_id": -1  # -1 indicates no specific model
    }
    logger.info(f"Creating PAUSE command: {command}")
    return json.dumps(command)


def create_play_command(timestamp: int, model_id: int) -> str:
    """
    Create a play command for the inference engine.
    
    Args:
        timestamp: Unix timestamp of the play request
        model_id: ID of the new model to load after resuming
        
    Returns:
        JSON string for play command
    """
    command = {
        "type": "play",
        "timestamp": timestamp,
        "model_id": model_id
    }
    logger.info(f"Creating PLAY command with model_id={model_id}: {command}")
    return json.dumps(command)
