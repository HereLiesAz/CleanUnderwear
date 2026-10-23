# Research Agent Model Card

## What it is
`research_agent.tflite`: a small text classifier, built with TensorFlow/Keras and converted for on-device inference with LiteRT (`OnDeviceResearchAgent`). Its training data and training code are not in this repository.

## What it does in the app
One job: when contacts are harvested (`HarvestContactsUseCase`), it scores whether a contact's display name reads like a person.

- **Input**: the display name, as a raw string (preprocessed against `research_agent_vocab.txt`).
- **Output**: a sigmoid score, 0.0 to 1.0.
- **Decision**: a name passes when the score is above **0.3** *or* a heuristic says it looks like a name (letters, no digits, a space or more than one letter), and it contains no service word as a whole word ("customer", "service", "support", "help", "bank", "office", "pizza", "taxi", "delivery", "store").
- **On failure**: if the model can't be loaded, the score is 1.0 and the heuristic and service-word rules decide alone.
- A name that fails is kept, renamed "Unnamed Entity (…)", and is not searched.

It does not classify search results, sources, obituaries or court records; no model is used in matching. Matching is rule-based (`RecordExtractor`, `MatchVerifier`).

## Assets
- `research_agent.tflite`: the model.
- `research_agent_vocab.txt`: its vocabulary.
- `nicknames.json`: a nickname table (Robert → Bob, Bobby, …), used by duplicate detection (`ProbabilisticScorer`, `DeduplicateTargetsUseCase`) to see that "Bob Smith" and "Robert Smith" may be one contact. It is not used to widen any records search.
