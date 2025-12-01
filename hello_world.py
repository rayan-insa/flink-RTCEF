"""
A simple Hello World example using Apache Flink's Python framework (PyFlink).

This demonstrates the basic structure of a PyFlink application that creates
a simple data stream and prints "Hello, World!" messages.
"""

from pyflink.datastream import StreamExecutionEnvironment


def main():
    """
    Main function that creates a simple Flink streaming job.
    
    This example:
    1. Creates a StreamExecutionEnvironment
    2. Creates a simple data stream from a collection
    3. Maps the data to print "Hello, World!" messages
    4. Executes the Flink job
    """
    # Create a StreamExecutionEnvironment
    env = StreamExecutionEnvironment.get_execution_environment()
    
    # Create a simple data stream from a collection
    data_stream = env.from_collection(
        collection=["Hello", "World", "from", "Apache", "Flink!"],
        type_info=None  # Let Flink infer the type
    )
    
    # Map each element to a greeting message and print
    data_stream.map(lambda x: f"Hello, World! Message: {x}").print()
    
    # Execute the Flink job
    env.execute("Hello World PyFlink Job")


if __name__ == "__main__":
    main()
