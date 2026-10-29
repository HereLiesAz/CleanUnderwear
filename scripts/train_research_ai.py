# HISTORICAL: trained the TFLite classifier that was later removed from the app (docs/MODEL_CARD.md).
# Not used by the app or CI. See scripts/README.md.
import os
import tensorflow as tf
import numpy as np

# 1. Dataset Generation (Synthetic)
# We train a model to recognize official government and sheriff websites vs commercial ones.
# Format: "domain.com Page Title"
positive_samples = [
    "opso.us Orleans Parish Sheriff's Office Inmate Roster",
    "stpso.com St. Tammany Parish Sheriff's Office",
    "nola.com/obituaries New Orleans Obituaries",
    "lasd.org Los Angeles County Sheriff's Department Inmate Information Center",
    "bexar.org Bexar County Jail Search",
    "cookcountysheriff.org Cook County Sheriff's Office Inmate Locator",
    "maricopa.gov Maricopa County Sheriff's Office Mugshots",
    "miamidade.gov Miami-Dade Corrections and Rehabilitation Inmate Search",
    "sheriff.org Broward County Sheriff's Office Arrest Search",
    "ocsd.org Orange County Sheriff's Department Who's in Jail",
    "tcsheriff.org Travis County Sheriff's Office Inmates",
    "hcso.tampa.fl.us Hillsborough County Sheriff's Office Arrest Inquiry",
    "sheriffleefl.org Lee County Sheriff's Office Arrest Search",
    "pbso.org Palm Beach County Sheriff's Office Booking Blotter",
    "legacy.com Obituaries",
    "dignitymemorial.com Recent Obituaries",
    "tributearchive.com Obituary Archive"
] * 20  # Duplicate to increase training data size

negative_samples = [
    "jailbase.com JailBase Arrests and Mugshots",
    "bustednewspaper.com Busted Newspaper Mugshots",
    "mugshots.com Mugshots Online Arrest Records",
    "arrests.org Recent Arrests",
    "news.yahoo.com Man arrested in local county",
    "foxnews.com Crime news suspect detained",
    "cnn.com Local jail sees overcrowding",
    "reddit.com/r/news Arrest records thread",
    "facebook.com John Doe profile",
    "twitter.com Police scanner tweets",
    "localnews.com Obituary of local resident",
    "spokeo.com Background check",
    "truthfinder.com Find public records",
    "beenverified.com Arrest lookup",
    "tiktok.com video on local arrest",
    "youtube.com News report on jail",
    "instagram.com photo of suspect"
] * 20

X_train = np.array(positive_samples + negative_samples)
y_train = np.array([1.0] * len(positive_samples) + [0.0] * len(negative_samples))

# Shuffle
indices = np.arange(len(X_train))
np.random.shuffle(indices)
X_train = X_train[indices]
y_train = y_train[indices]

# 2. Model Architecture
VOCAB_SIZE = 1000
EMBEDDING_DIM = 16

vectorize_layer = tf.keras.layers.TextVectorization(
    max_tokens=VOCAB_SIZE,
    output_mode='int',
    output_sequence_length=20
)

# Adapt the vectorization layer
vectorize_layer.adapt(X_train)

model = tf.keras.Sequential([
    vectorize_layer,
    tf.keras.layers.Embedding(VOCAB_SIZE, EMBEDDING_DIM, name="embedding"),
    tf.keras.layers.GlobalAveragePooling1D(),
    tf.keras.layers.Dense(16, activation='relu'),
    tf.keras.layers.Dense(1, activation='sigmoid')
])

model.compile(optimizer='adam',
              loss='binary_crossentropy',
              metrics=['accuracy'])

# 3. Train
print("Training the Tiny Research AI Model...")
model.fit(X_train, y_train, epochs=20, batch_size=16, verbose=1)

# 4. Export to TFLite
print("Exporting to TFLite...")

# We must create a wrapper to ensure the input is string tensor
@tf.function(input_signature=[tf.TensorSpec(shape=[None], dtype=tf.string)])
def inference_fn(inputs):
    return {"scores": model(inputs)}

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS, # enable TensorFlow Lite ops.
    tf.lite.OpsSet.SELECT_TF_OPS # enable TensorFlow ops.
]

# Note: TextVectorization relies on Select TF Ops. 
tflite_model = converter.convert()

target_dir = "app/src/main/assets"
os.makedirs(target_dir, exist_ok=True)
model_path = os.path.join(target_dir, "research_agent.tflite")

with open(model_path, "wb") as f:
    f.write(tflite_model)

# Save vocabulary for manual tokenization if TFLite Text Ops are not available
vocab = vectorize_layer.get_vocabulary()
vocab_path = os.path.join(target_dir, "research_agent_vocab.txt")
with open(vocab_path, "w", encoding="utf-8") as f:
    for word in vocab:
        f.write(f"{word}\n")

print(f"Model successfully saved to {model_path} ({len(tflite_model) / 1024:.2f} KB)")
