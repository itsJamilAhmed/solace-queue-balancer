# Solace Queue Balancer

## Table of Contents :bookmark_tabs:  
* [Repository Purpose](#repository-purpose)
* [Implementation Principles](#implementation-principles)
* [Running the tool](#running-the-tool)
* [Example Program Output](#example-program-output)
* [Importing into Eclipse IDE](#importing-into-eclipse-ide)
* [Contributing](#contributing)
* [Authors](#authors)
* [License](#license)
* [Resources](#resources)

## Repository Purpose

When using Solace PubSub+ Event Brokers, the backlog of events/messages in a queue can be processed in a [competing consumers pattern](https://solace.com/event-driven-architecture-patterns/#competing-consumers). This is where multiple consumers can join the same queue to balance the processing load across all available consumers.

For some very specific use-cases, the number of consumers planning to participate in the competition is exceptionally large. (i.e. Greater than the 10,000 concurrent consumers limit for a single PubSub+ Event Broker queue.)

While such use-cases can plan to horizontally scale across multiple queues on the same broker, a new concern would be getting into an imbalance situation of some queues being empty, and therefore consumer capacity sitting idle, when at the same time there are other queues having a backlog.
Fortunately, the PubSub+ Event Broker provides a configuration, monitoring and action API called [SEMP](https://docs.solace.com/Admin/SEMP/Using-SEMP.htm) that can be used to build a tool to easily monitor and 'rebalance' such queues. This is done by moving messages across the set of queues to leave the outstanding backlog evenly distributed.

This repository will provide a working sample 'queue balancer' utility in the context of this **[grid-computing](https://en.wikipedia.org/wiki/Grid_computing)** use-case that needs to scale to 10's of thousands of competing consumers. 

## Implementation Principles

1. Queue rebalancing will only be triggered if the current depth is more than 10% away from the calculated per-queue average target depth
2. When moving messages from a queue, the choice will be the oldest message in the queue that has not been delivered to any consumer. (i.e. `undelivered` is `true`)
   * If all the available messages are marked `undelivered` as `false`, then those messages will be selected to move too.
   * Note: These are messages that were assigned to a consumer at least once before, but failed to get consumed. So will get 'seen' again by a consumer on the second queue.
3. If during message moves the source queue becomes empty, it signals that the backlog has cleared from other parallel reasons, so the process will continue onto the next queue to rebalance backlog from other queues.
4. If any unexpected SEMP API errors are returned, the whole rebalancing operation is abandoned.

## Running the tool

### Pre-requisites :white_check_mark:

* Java version 17 or higher
* Event Broker version 10.1.0 (SEMP v2.29) or higher
* SEMP credentials with read/write access for the message-vpn (to perform the message moves)

### Step :one:: Create local clone of repository

The repository is a Gradle multi-module project consisting of the following:
1. `app` - The core of the tool, two Java classes making up the 'Queue Balancer' capability and a Main program using the provided capability.
2. `semp-lib` - This is an empty directory that will be populated with generated code using the [OpenAPI](https://www.openapis.org/) specifications for the SEMP API.
3. `semp-lib-codegen` - This contains a Gradle task that can automatically download the SEMP OpenAPI specifications and generate the Java code needed to interact with the API. (There is also a README file detailing how to do this code generation manually.)

Clone this GitHub repository to a suitable location:

```
git clone https://github.com/itsJamilAhmed/solace-queue-balancer/
cd solace-queue-balancer
```

### Step :two:: Code generation and build using Gradle

Use the included Gradle Wrapper script to build the project. This will perform the following steps:
1. Test whether the `semp-lib` directory is empty. If so, Java classes will be generated first.
2. Compile all the Java classes
3. Create an executable Jar of the program

```
./gradle :semp-lib-codegen:buildSempLibs
./gradlew build
./gradlew shadowJar
cp ./app/build/libs/QueueBalancerApp.jar .
```

### Step :three:: Run executable jar

The program needs the following arguments:
* `--semp-base` - The URI to the SEMP path on your Event Broker. e.g.  https://mysolace:943/SEMP/v2/config
* `--message-vpn` - The Message VPN containing the queues to monitor and rebalance
* `--semp-user` - The admin user with read/write permission
* `--semp-password` - The password of the admin user
* `--queues-list` - Comma delimited list of queues to monitor and rebalance. e.g. myQueue1,myQueue2,myQueue3

```
java -jar ./QueueBalancerApp.jar --semp-base https://mysolace:943/SEMP/v2/config --message-vpn jamil_dev --semp-user admin --semp-password <here> --queues-list balancerTool_Q1,balancerTool_Q2,balancerTool_Q3,balancerTool_Q4,balancerTool_Q5,balancerTool_Q6
```

## Example Program Output 

### No rebalancing needed:
```
11:43:41.696 [main] INFO : sempBase: https://mysolace:943
11:43:42.546 [main] INFO :      Status for queue: balancerTool_Q1... Current Depth: 34. Clients Bound? true
11:43:42.597 [main] INFO :      Status for queue: balancerTool_Q2... Current Depth: 34. Clients Bound? true
11:43:42.655 [main] INFO :      Status for queue: balancerTool_Q3... Current Depth: 39. Clients Bound? true
11:43:42.701 [main] INFO :      Status for queue: balancerTool_Q4... Current Depth: 34. Clients Bound? true
11:43:42.750 [main] INFO :      Status for queue: balancerTool_Q5... Current Depth: 34. Clients Bound? true
11:43:42.797 [main] INFO :      Status for queue: balancerTool_Q6... Current Depth: 34. Clients Bound? true
11:43:42.809 [main] INFO : Total number of messages across queues: 209
11:43:42.810 [main] INFO : Number of queues with clients bound: 6
11:43:42.813 [main] INFO : Average target per queue if balanced: 34.0
11:43:42.814 [main] INFO : Modulus remainder to account for: 5
11:43:42.814 [main] INFO : Determining message count changes needed for each queue to achieve balanced state...
11:43:42.818 [main] INFO :      Set depth change target for queue balancerTool_Q1 to: 0
11:43:42.818 [main] INFO :      Set depth change target for queue balancerTool_Q2 to: 0
11:43:42.819 [main] INFO :      Set depth change target for queue balancerTool_Q3 to: -5
11:43:42.820 [main] INFO :      Set depth change target for queue balancerTool_Q4 to: 0
11:43:42.820 [main] INFO :      Set depth change target for queue balancerTool_Q5 to: 0
11:43:42.821 [main] INFO :      Set depth change target for queue balancerTool_Q6 to: 0
11:43:42.826 [main] INFO : Do the queues need rebalancing? false
```

### Rebalancing was needed:
```
11:49:26.882 [main] INFO : sempBase: https://mysolace:943
11:49:27.451 [main] INFO :      Status for queue: balancerTool_Q1... Current Depth: 44. Clients Bound? true
11:49:27.499 [main] INFO :      Status for queue: balancerTool_Q2... Current Depth: 34. Clients Bound? true
11:49:27.549 [main] INFO :      Status for queue: balancerTool_Q3... Current Depth: 39. Clients Bound? true
11:49:27.598 [main] INFO :      Status for queue: balancerTool_Q4... Current Depth: 34. Clients Bound? true
11:49:27.648 [main] INFO :      Status for queue: balancerTool_Q5... Current Depth: 34. Clients Bound? true
11:49:27.699 [main] INFO :      Status for queue: balancerTool_Q6... Current Depth: 49. Clients Bound? true
11:49:27.706 [main] INFO : Total number of messages across queues: 234
11:49:27.708 [main] INFO : Number of queues with clients bound: 6
11:49:27.710 [main] INFO : Average target per queue if balanced: 39.0
11:49:27.711 [main] INFO : Modulus remainder to account for: 0
11:49:27.711 [main] INFO : Determining message count changes needed for each queue to achieve balanced state...
11:49:27.715 [main] INFO :      Set depth change target for queue balancerTool_Q1 to: -5
11:49:27.716 [main] INFO :      Set depth change target for queue balancerTool_Q2 to: 5
11:49:27.716 [main] INFO :      Set depth change target for queue balancerTool_Q3 to: 0
11:49:27.717 [main] INFO :      Set depth change target for queue balancerTool_Q4 to: 5
11:49:27.717 [main] INFO :      Set depth change target for queue balancerTool_Q5 to: 5
11:49:27.718 [main] INFO :      Set depth change target for queue balancerTool_Q6 to: -10
11:49:27.721 [main] INFO : Do the queues need rebalancing? true
11:49:27.722 [main] INFO : >>> Rebalance operation started...
11:49:27.724 [main] INFO : Number of reducing queues: 2
11:49:27.725 [main] INFO : Number of increasing queues: 4
11:49:27.726 [main] INFO : Number of messages to move: 15
11:49:28.034 [main] INFO :      Moved message 1 with ID: rmid1:2542a-737bb6c39e6-00000000-00002866 from balancerTool_Q1 to balancerTool_Q2
11:49:28.163 [main] INFO :      Moved message 2 with ID: rmid1:2542a-737bb6c39e6-00000000-00002867 from balancerTool_Q1 to balancerTool_Q2
11:49:28.294 [main] INFO :      Moved message 3 with ID: rmid1:2542a-737bb6c39e6-00000000-00002868 from balancerTool_Q1 to balancerTool_Q2
11:49:28.422 [main] INFO :      Moved message 4 with ID: rmid1:2542a-737bb6c39e6-00000000-00002869 from balancerTool_Q1 to balancerTool_Q2
11:49:28.562 [main] INFO :      Moved message 5 with ID: rmid1:2542a-737bb6c39e6-00000000-0000286a from balancerTool_Q1 to balancerTool_Q2
11:49:28.703 [main] INFO :      Moved message 6 with ID: rmid1:2542a-737bb6c39e6-00000000-00002809 from balancerTool_Q6 to balancerTool_Q4
11:49:28.846 [main] INFO :      Moved message 7 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280a from balancerTool_Q6 to balancerTool_Q4
11:49:28.975 [main] INFO :      Moved message 8 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280b from balancerTool_Q6 to balancerTool_Q4
11:49:29.108 [main] INFO :      Moved message 9 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280c from balancerTool_Q6 to balancerTool_Q4
11:49:29.234 [main] INFO :      Moved message 10 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280d from balancerTool_Q6 to balancerTool_Q4
11:49:29.366 [main] INFO :      Moved message 11 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280e from balancerTool_Q6 to balancerTool_Q5
11:49:29.495 [main] INFO :      Moved message 12 with ID: rmid1:2542a-737bb6c39e6-00000000-0000280f from balancerTool_Q6 to balancerTool_Q5
11:49:29.620 [main] INFO :      Moved message 13 with ID: rmid1:2542a-737bb6c39e6-00000000-00002810 from balancerTool_Q6 to balancerTool_Q5
11:49:29.746 [main] INFO :      Moved message 14 with ID: rmid1:2542a-737bb6c39e6-00000000-00002811 from balancerTool_Q6 to balancerTool_Q5
11:49:29.886 [main] INFO :      Moved message 15 with ID: rmid1:2542a-737bb6c39e6-00000000-00002812 from balancerTool_Q6 to balancerTool_Q5
11:49:29.887 [main] INFO : >>> Rebalance operation completed successfully after 100% of planned moves.
```

## Importing into Eclipse IDE

To modify the `QueueBalancerApp` and `QueueBalancer` classes in Eclipse, do the following:

1. At `solace-queue-balancer` root directory:
```
gradlew eclipse
```

2. From Eclipse `File > Import > Import Existing Projects into Workspace` and provide the path to the top level `solace-queue-balancer` directory.
3. Select to import both the `app` and `semp-libs` projects that will be listed

The `QueueBalancerApp` class containing Main can then be _'Run as'_ a Java Application. All dependencies would be handled in the project's classpath. 

## Contributing

Feedback and suggestions are welcome. Please raise an issue in the repo.

## Authors

See the list of [contributors](https://github.com/itsJamilAhmed/solace-queue-balancer/graphs/contributors) who participated in this project 

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Resources

For more information try these resources:
- Developer [tutorials/codelabs](https://tutorials.solace.dev/semp) using the SEMP API
- Solace SEMP Samples Repo in [GitHub](https://github.com/SolaceSamples/solace-samples-semp)
- Get a better understanding of [Solace Event Brokers](https://solace.com/products/event-broker/)
- The Solace [Developer Portal](https://solace.dev)
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community](https://solace.community/) for help
