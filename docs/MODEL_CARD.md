# Model Card: none shipped

The app contains no machine-learning model. Name screening and record matching are rules, written out in the code.

## What used to be here
Earlier versions shipped `research_agent.tflite` and ran it through LiteRT (with the TensorFlow select-ops runtime) inside `OnDeviceResearchAgent.validatePersonName`, OR-ing its score into the name rules. That model is the one `scripts/train_research_ai.py` and `ml/CleanUnderwear.ipynb` build: a classifier of **official sheriff and government websites versus commercial ones**, trained on strings such as "opso.us Orleans Parish Sheriff's Office Inmate Roster". Its vocabulary (`roster`, `sheriff`, `dpstexasgov`, …) showed it. Applied to a person's name its score meant nothing, and it could only let through a name the rules had rejected. It was removed, with the runtime.

## What the app does instead
- **Is this a person's name?** (`OnDeviceResearchAgent.validatePersonName`, used when contacts are read): no digits; two or more words, or one word of letters longer than one character; no business word as a whole word ("customer", "service", "support", "help", "bank", "office", "pizza", "taxi", "delivery", "store"). A name that fails is kept, renamed "Unnamed Entity (…)", and not searched.
- **Nicknames** (`nicknames.json`): Robert → Bob, Bobby, …, used by duplicate detection (`ProbabilisticScorer`, `DeduplicateTargetsUseCase`) to see that "Bob Smith" and "Robert Smith" may be one contact.
- **Matching a record to a contact** is rule-based (`RecordExtractor`, `MatchVerifier`); see the Monitoring Guide.

## The training code
`scripts/train_research_ai.py` and `ml/CleanUnderwear.ipynb` remain in the repository as history. Nothing the app ships is built from them.
