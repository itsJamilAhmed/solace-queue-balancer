The contents of semp-libs directory are fully generated from OpenAPI Specs for the three SEMP APIs (config, monitor, action).

Using openapi-generator-cli tool, generate the source files as follows:

# Get the 3 specs:
wget https://products.solace.com/download/PUBSUB_SEMPV2_SCHEMA_YAML -O solace-sempv2-openapi-config.yaml
wget https://products.solace.com/download/PUBSUB_SEMPV2_SCHEMA_MONITOR_YAML -O solace-sempv2-openapi-monitor.yaml
wget https://products.solace.com/download/PUBSUB_SEMPV2_SCHEMA_ACTION_YAML -O solace-sempv2-openapi-action.yaml

# Generate code from specs:
java -jar openapi-generator-cli-7.8.0.jar generate -i solace-sempv2-openapi-config.yaml  -g java -o ../semp-libs -c config-for-generator-semp-config.yaml
java -jar openapi-generator-cli-7.8.0.jar generate -i solace-sempv2-openapi-monitor.yaml -g java -o ../semp-libs -c config-for-generator-semp-monitor.yaml
java -jar openapi-generator-cli-7.8.0.jar generate -i solace-sempv2-openapi-action.yaml  -g java -o ../semp-libs -c config-for-generator-semp-action.yaml

# The dependencies in the generated build.gradle file will also be dependencies for the using application.
