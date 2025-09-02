import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Documentation Forms

## Why Would You Use Documentation Forms?

Documentation Forms are a way for end-users to fill out all mandatory attributes associated with a data asset. The form will be dynamically generated based on the definitions provided by administrators and stewards and matching rules.

Learn more about forms in the [Documentation Forms Feature Guide](../../../docs/features/feature-guides/documentation-forms.md).


### Goal Of This Guide
This guide will show you how to create and read forms.

## Prerequisites

For this tutorial, you need to deploy DataHub Quickstart and ingest sample data.
For detailed information, please refer to [Datahub Quickstart Guide](/docs/quickstart.md).


<Tabs>
<TabItem value="CLI" label="CLI">

Install the relevant CLI version. Forms are available as of CLI version `0.13.1`. The corresponding SaaS release version is `v0.2.16.5`
Connect to your instance via [init](https://datahubproject.io/docs/cli/#init):

1. Run `datahub init` to update the instance you want to load into
2. Set the server to your sandbox instance, `https://{your-instance-address}/gms`
3. Set the token to your access token


</TabItem>
</Tabs>


## Create a Form

<Tabs>
<TabItem value="CLI" label="CLI">

Create a yaml file representing the forms you’d like to load. 
For example, below file represents a form `123456` You can see the full example [here](https://github.com/datahub-project/datahub/blob/example-yaml-sp/metadata-ingestion/examples/forms/forms.yaml).
        

```yaml
- id: 123456
  # urn: "urn:li:form:123456"  # optional if id is provided
  type: VERIFICATION # Supported Types: DOCUMENTATION, VERIFICATION
  name: "Metadata Initiative 2023"
  description: "How we want to ensure the most important data assets in our organization have all of the most important and expected pieces of metadata filled out"
  prompts:
    - id: "123"
      title: "Retention Time"
      description: "Apply Retention Time structured property to form"
      type: STRUCTURED_PROPERTY
      structured_property_id: io.acryl.privacy.retentionTime
      required: True # optional, will default to True
  entities: # Either pass a list of urns or a group of filters. This example shows a list of urns
    urns:
      - urn:li:dataset:(urn:li:dataPlatform:hdfs,SampleHdfsDataset,PROD)
  # optionally assign the form to a specific set of users and/or groups
  # when omitted, form will be assigned to Asset owners
  actors: 
    users:
      - urn:li:corpuser:jane@email.com  # note: these should be urns
      - urn:li:corpuser:john@email.com
    groups:
      - urn:li:corpGroup:team@email.com  # note: these should be urns
```

:::note
Note that the structured properties and related entities should be created before you create the form. 
Please refer to the [Structured Properties Tutorial](/docs/api/tutorials/structured-properties.md) for more information.
:::


You can apply forms to either a list of entity urns, or a list of filters. For a list of entity urns, use this structure:
    
```
entities:
urns:
  - urn:li:dataset:...
```
    
For a list of filters, use this structure:
    
```
entities:
filters:
  types:
    - dataset  # you can use entity type name or urn
  platforms:
    - snowflake  # you can use platform name or urn
  domains:
    - urn:li:domain:finance  # you must use domain urn
  containers:
    - urn:li:container:my_container  # you must use container urn
```

Note that you can filter to entity types, platforms, domains, and/or containers.

Use the CLI to create your properties:

```commandline
datahub forms upsert -f {forms_yaml}
```

If successful, you should see `Created form urn:li:form:...`

</TabItem>
</Tabs>

## Read Property Definition


<Tabs>
<TabItem value="CLI" label="CLI">

You can see the properties you created by running the following command:

```commandline
datahub forms get --urn {urn}
```
For example, you can run `datahub forms get --urn urn:li:form:123456`.

If successful, you should see metadata about your form returned like below.

```json
{
  "urn": "urn:li:form:123456",
  "name": "Metadata Initiative 2023",
  "description": "How we want to ensure the most important data assets in our organization have all of the most important and expected pieces of metadata filled out",
  "prompts": [
    {
      "id": "123",
      "title": "Retention Time",
      "description": "Apply Retention Time structured property to form",
      "type": "STRUCTURED_PROPERTY",
      "structured_property_urn": "urn:li:structuredProperty:io.acryl.privacy.retentionTime"
    }
  ],
  "type": "VERIFICATION"
}
```

</TabItem>
</Tabs>
