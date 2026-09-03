# Spring Order Service Golden Fixture

Static-analysis input only. The analyzer never runs this Maven build or application.
The baseline minimum is 500; the sibling `spring-order-service-revision2` changes it to 300.
Both revisions include PostgreSQL defaults and an INSERT audit trigger. Java extraction
reports ORM declarations; the PostgreSQL analyzer establishes migration/default/trigger facts.
JUnit source links are static call evidence, not a claim that those tests ran or cover every rule.
