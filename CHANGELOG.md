# Changelog

## [0.3.0](https://github.com/elixir-metatrack/api-server/compare/0.2.0...0.3.0) (2026-08-19)


### Features

* add global sample vocabulary management with CRUD operations ([991c9e5](https://github.com/elixir-metatrack/api-server/commit/991c9e53309c7d98542394b08b4e5f8596a5c176))
* add Keycloak admin client configuration ([3689b6c](https://github.com/elixir-metatrack/api-server/commit/3689b6ce4830dee57a92323e9be2042d54cae28f))
* add Keycloak user lookup service ([5652e7f](https://github.com/elixir-metatrack/api-server/commit/5652e7fdae6c8d590425dd9925fc2f9b24886ca2))
* add role-claim-path configuration for Keycloak roles mapping ([70b3013](https://github.com/elixir-metatrack/api-server/commit/70b3013173af0c3cdb0f93fea4e9412f9ff6f723))
* enhance error handling for Keycloak user lookup service ([56fe287](https://github.com/elixir-metatrack/api-server/commit/56fe287706321b3dc4000f958cda9a9f9d83e3bc))
* enhance KeycloakIdentityException logging with detailed cause chain tracing ([d9bba52](https://github.com/elixir-metatrack/api-server/commit/d9bba520231eda463429c5effd4334a1aeebc709))
* extend support for global and project sample vocabularies ([d9a49d5](https://github.com/elixir-metatrack/api-server/commit/d9a49d534e041d75346aa2e4f65d26684d8a9c5a))
* integrate username resolution in project services ([b160df1](https://github.com/elixir-metatrack/api-server/commit/b160df157119972466f5c227a94eda409cad8112))


### Bug Fixes

* **deps:** update aws-java-sdk-v2 monorepo to v2.53.3 ([03ba061](https://github.com/elixir-metatrack/api-server/commit/03ba06110cbf9da3337247472b4b98c719884c9c))
* **deps:** update quarkus ecosystem to v3.38.2 ([d289081](https://github.com/elixir-metatrack/api-server/commit/d289081357bd05ef08255ee72dcad6c80f1105ad))


### Tests

* add test coverage for keycloak admin client integration ([2696cee](https://github.com/elixir-metatrack/api-server/commit/2696cee153e246e55362825376333d6bdc408156))
* add unit tests for built-in field rejection and vocabulary rule composition ([f555f1f](https://github.com/elixir-metatrack/api-server/commit/f555f1f5c44c104b1dfbf2620c248209718c38be))
* add unit tests for global sample vocabulary management and migration ([e28ab71](https://github.com/elixir-metatrack/api-server/commit/e28ab715fc3832a5efa866b555a4890f68a82e2a))


### Miscellaneous Chores

* **main:** release 0.2.1-SNAPSHOT ([2cb474e](https://github.com/elixir-metatrack/api-server/commit/2cb474ecde981cb0d48ce2dc1d975f5787e1d648))
* update .env.example with extended Keycloak configuration options ([e546069](https://github.com/elixir-metatrack/api-server/commit/e546069a6171cf67a5707c504a0ec78f28861acb))

## [0.2.0](https://github.com/elixir-metatrack/api-server/compare/0.1.0...0.2.0) (2026-08-11)


### Features

* add daily sample count APIs and pagination logic ([3748fdc](https://github.com/elixir-metatrack/api-server/commit/3748fdcdefd51202b820e22e24bee9f35fb3dfa0))
* add Flyway migration for custom sample metadata tables ([71ebad4](https://github.com/elixir-metatrack/api-server/commit/71ebad4a5f8108b820edffb74d20dc7bb51427db))
* add Flyway migration for custom sample metadata tables ([412e014](https://github.com/elixir-metatrack/api-server/commit/412e014c49541be740c2ae81f33fa33e0b31e686))
* add Flyway migration for custom sample metadata tables ([c007ef8](https://github.com/elixir-metatrack/api-server/commit/c007ef8f57b054ae542af3b4ce7802ecca2b8c6a))
* add metadata fields to `sample` table and update Sample model ([7e036f9](https://github.com/elixir-metatrack/api-server/commit/7e036f9a3f227e81112c70cc58792a56debbbecc))
* add metadata models for samples and projects ([6a7467a](https://github.com/elixir-metatrack/api-server/commit/6a7467afdb9e893852bd33a11583cfb0efd618bd))
* add sample vocabulary functionality ([58daa17](https://github.com/elixir-metatrack/api-server/commit/58daa1725afd15562daa2a83c6cda8361dffd46f))
* add sample vocabulary models and Flyway migration ([f603432](https://github.com/elixir-metatrack/api-server/commit/f6034320567bd48f4b8c90306a44bd870e051a53))
* associate files with both samples and assays ([cd3574c](https://github.com/elixir-metatrack/api-server/commit/cd3574c1c851bbb4391697db1a9151aa608d5080))
* automate releases with Release Please integration ([fd3dced](https://github.com/elixir-metatrack/api-server/commit/fd3dced28399d2c3e00bfc36b04204ac0b25597f))
* **config:** enable Flyway migrations at application startup ([172e7d1](https://github.com/elixir-metatrack/api-server/commit/172e7d1c961d9aa0d6b33ac6a170a6944896f32d))
* **config:** externalize sensitive config values to env vars ([57dc233](https://github.com/elixir-metatrack/api-server/commit/57dc233be7b99bde432d2e3703a9653c574295f9))
* extend sample model and services to support custom metadata ([94fa01d](https://github.com/elixir-metatrack/api-server/commit/94fa01db3d10abeb5499c8498e09b7ddb7546fce))
* extend sample model and services to support custom metadata ([cf72f05](https://github.com/elixir-metatrack/api-server/commit/cf72f05fd6b2ef7ba46fb0e5f5f30ff8d34a6eeb))
* extend user model with country, institution, and orcid properties ([5544140](https://github.com/elixir-metatrack/api-server/commit/5544140e38dbc8187ca967a162e3b900e30cb420))
* replace MinIO integration with S3-compatible storage and add upload reconciliation ([1f30c75](https://github.com/elixir-metatrack/api-server/commit/1f30c758faa34de4217a048e11606a90b49471fd))
* track file uploader with `uploaded_by` field and update related models ([d9da885](https://github.com/elixir-metatrack/api-server/commit/d9da885864468bfd52cb8fe66afea720b6498bab))
* update CRUD to include additional fields in sample model ([9bc5b79](https://github.com/elixir-metatrack/api-server/commit/9bc5b799c8b326b71884bf640b6481f009ac3eac))


### Bug Fixes

* **deps:** update aws-java-sdk-v2 monorepo to v2.48.1 ([6779b86](https://github.com/elixir-metatrack/api-server/commit/6779b865ee87ec31b8ccd46b7c531351afe576e1))
* **deps:** update aws-java-sdk-v2 monorepo to v2.51.4 ([7bcd861](https://github.com/elixir-metatrack/api-server/commit/7bcd861814d727406455188aa5d7c656508df0d6))
* **deps:** update quarkus ecosystem to v3.35.4 ([08d586e](https://github.com/elixir-metatrack/api-server/commit/08d586e29e0a52867e58c5d94fb5d294e36d84a9))
* **deps:** update quarkus ecosystem to v3.36.1 ([3c11eb9](https://github.com/elixir-metatrack/api-server/commit/3c11eb9afdb4455a872b4dfea4d92722e80f6bc6))
* **deps:** update quarkus ecosystem to v3.36.2 ([1c17037](https://github.com/elixir-metatrack/api-server/commit/1c170379107c77bf52651c5c044e3a6c6f0fd472))
* **deps:** update quarkus ecosystem to v3.36.3 ([95c13f1](https://github.com/elixir-metatrack/api-server/commit/95c13f1f9978895b4445a38ef6f6a2e7d390ab08))
* **deps:** update quarkus ecosystem to v3.37.1 ([76f14e9](https://github.com/elixir-metatrack/api-server/commit/76f14e9a09b7d0893bba8332e52c762c77b57936))
* **deps:** update quarkus ecosystem to v3.37.3 ([57c9575](https://github.com/elixir-metatrack/api-server/commit/57c95750c485f2c87fbd7858b7879b577cd29416))
* **deps:** update quarkus ecosystem to v3.38.1 ([25d2167](https://github.com/elixir-metatrack/api-server/commit/25d21678ff4cb7c99cc724ad0da2e65e7df20015))
* fix file service logic to handle sample and assay pair check ([1168d6f](https://github.com/elixir-metatrack/api-server/commit/1168d6f9eec6cb1618a629192efb1140b862d00b))
* return empty list instead of null in AssayService ([b432ac0](https://github.com/elixir-metatrack/api-server/commit/b432ac0f867e4e8937627cc3dd0770eb8d2cb917))


### Documentation

* update README with sample vocabulary feature details and API usage ([685353d](https://github.com/elixir-metatrack/api-server/commit/685353d8625c7e66b87c326a2fc2385ee4b7793f))


### Tests

* add unit tests for sample vocabulary controllers, services, and CSV imports ([7c3026a](https://github.com/elixir-metatrack/api-server/commit/7c3026a03125554438c1c8d7d13eb689d6bdcaf7))


### Miscellaneous Chores

* **deps:** update actions/checkout action to v7 ([9da6fb8](https://github.com/elixir-metatrack/api-server/commit/9da6fb819479ffc0872c8850be38afca530efdac))
* **deps:** update dependency maven to v3.9.16 ([2d653db](https://github.com/elixir-metatrack/api-server/commit/2d653dbfd5be1e9ecf1675a1a4f572d975768dcc))
* **deps:** update dependency org.mockito:mockito-junit-jupiter to v5.23.0 ([54dbc5a](https://github.com/elixir-metatrack/api-server/commit/54dbc5a13308ff386032289ee1f39aae851e1973))
* **deps:** update maven plugins to v3.5.6 ([005c676](https://github.com/elixir-metatrack/api-server/commit/005c6760455fce6d5c336c294c75a39c37a710b3))
* **main:** release 0.1.1-SNAPSHOT ([47d8a84](https://github.com/elixir-metatrack/api-server/commit/47d8a84e1d4a036af696c3b204496f05b6f732e0))
