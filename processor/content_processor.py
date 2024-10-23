import base64
import sys
from io import BytesIO

from detoxify import Detoxify
from PIL import Image
from transformers import CLIPModel, CLIPProcessor


def process_content(content_type: str, content: bytes):
    if content_type == "image":
        process_image(content)
    elif content_type == "text":
        process_text(content)
    else:
        print("Invalid content type. Please specify 'image' or 'text'.")


def process_image(content: bytes):
    image = Image.open(BytesIO(content))
    model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
    processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
    prompts = [
        "hate symbols",
        "racist imagery",
        "nazi symbols",
        "white supremacist symbols",
        "antisemitic imagery",
        "islamophobic content",
        "homophobic imagery",
        "transphobic content",
        "sexist imagery",
        "misogynistic content",
        "violent imagery",
        "graphic violence",
        "gore",
        "animal cruelty",
        "child exploitation",
        "terrorist propaganda",
        "extremist symbols",
        "gang signs",
        "drug use",
        "self-harm",
        "suicide imagery",
        "pornographic content",
        "sexually explicit imagery",
        "offensive gestures",
        "blackface",
        "racist caricatures",
        "burning religious symbols",
        "desecration of sacred objects",
        "lynching imagery",
        "holocaust denial symbols",
        "KKK imagery",
        "neo-nazi symbols",
        "fascist imagery",
        "confederate flag",
        "noose",
        "swastika",
        "SS bolts",
        "white power hand signs",
        "burning cross",
        "hate speech",
        "dehumanizing imagery",
        "bullying content",
        "racial slurs",
        "gender-based violence",
        "xenophobic content",
        "antisocial behavior",
        "intimidation symbols",
        "hate crime imagery",
        "body shaming",
        "ageism",
        "ableism",
        "propaganda against minority groups",
        "religious intolerance",
        "radical extremist imagery",
        "incitement to violence",
        "cultural appropriation",
        "mocking disabilities",
        "scapegoating",
        "defamatory symbols",
        "obscene gestures",
        "discriminatory caricatures",
        "violent protest imagery",
        "child abuse imagery",
        "hate-based caricatures",
        "intolerance symbols",
    ]
    prompts = [prompt.replace(" ", "_") for prompt in prompts]
    inputs = processor(text=prompts, padding=True, images=image, return_tensors="pt")
    output = model(**inputs)
    score = output.logits_per_image
    toxicity = score.softmax(dim=1)
    toxicity = toxicity.squeeze(0)
    print({prompts: score.item() for prompts, score in zip(prompts, toxicity)})


def process_text(content: bytes):
    text = content.decode("utf-8")
    model = Detoxify("original")
    results = model.predict(text)

    print({category: "{:.10f}".format(score) for category, score in results.items()})


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(
            "Usage: python content_processor.py <content_type> <base64_encoded_content>. Content type is either an image or text."
        )
        sys.exit(1)

    content_type = sys.argv[1].lower()
    base64_content = sys.argv[2]

    try:
        content = base64.b64decode(base64_content)
    except:
        print("Error: Invalid base64 encoded content")
        sys.exit(1)

    process_content(content_type, content)
