enum DescriptionVerdict {
  GOOD @description("Specific, accurate and complete for the product type")
  ACCEPTABLE @description("Usable but missing some useful details")
  POOR @description("Vague, too short, misleading, with unverifiable claims, or missing")
}

enum CategoryVerdict {
  FITS @description("The assigned category is the best match in the provided list")
  QUESTIONABLE @description("The assigned category is plausible but another listed category may fit better")
  MISMATCH @description("The assigned category is clearly wrong for this product")
}

enum PriceVerdict {
  FAIR @description("The price is within the typical market range")
  ABOVE_MARKET @description("The price is above the typical market range")
  BELOW_MARKET @description("The price is below the typical market range")
  INSUFFICIENT_DATA @description("Use only when the product type cannot be identified from the name and description; an unknown brand alone is not a reason")
}

enum Confidence {
  LOW
  MEDIUM
  HIGH
}

class DescriptionAssessment {
  verdict DescriptionVerdict @description("Overall description quality")
  score int @description("1 (worst) to 10 (best)")
  summary string @description("One sentence on the description's quality")
  issues string[] @description("Concrete problems, max 4, each under 120 characters; empty when none")
  suggestions string[] @description("Actionable improvements, max 4, each under 120 characters; empty when none")
}

class CategoryAssessment {
  verdict CategoryVerdict @description("Fit of the assigned category")
  suggestedCategory string? @description("Exact name of a better category taken from the provided list; null when the verdict is FITS or no listed category fits better")
  reasoning string @description("One or two sentences")
}

class PriceAssessment {
  pricedAs string @description("The product type/class you priced, e.g. '65-inch OLED 4K TV' - the product itself, never the catalog peers")
  reasoning string @description("One or two sentences on typical retail prices for that product type, citing comparable products")
  verdict PriceVerdict @description("Price compared with typical US retail prices")
  estimatedMarketMinUsd float? @description("Lower bound of typical retail price in USD for the product type in pricedAs; null when unknown")
  estimatedMarketMaxUsd float? @description("Upper bound of typical retail price in USD for the product type in pricedAs; null when unknown")
  confidence Confidence @description("Confidence in the estimate")
}

class OfferAnalysis {
  description DescriptionAssessment @description("Assessment of the product description")
  category CategoryAssessment @description("Assessment of the assigned category")
  price PriceAssessment @description("Assessment of the product price")
}

class ProductInput {
  name string @description("Product name")
  description string @description(#"Product description, or "(no description)" when empty"#)
  price string @description("Product price formatted as USD 1799.99")
  categoryName string @description("Name of the assigned category")
}

class CategoryOption {
  name string @description("Category name")
  description string? @description("Category description")
}

class PeerPriceStats {
  categoryName string @description("Name of the category the peers belong to")
  count int @description("Number of other catalog products in the category")
  min string @description("Lowest peer price formatted as USD x.xx")
  median string @description("Median peer price formatted as USD x.xx")
  max string @description("Highest peer price formatted as USD x.xx")
}

class PeerProduct {
  name string @description("Peer product name")
  price string @description("Peer product price formatted as USD x.xx")
}

function AnalyzeOffer(
  product: ProductInput,
  categories: CategoryOption[],
  peerStats: PeerPriceStats?,
  peers: PeerProduct[]
) -> OfferAnalysis {
  client OfferAnalysisLlm
  prompt #"
    You are a catalog quality analyst for a consumer-electronics web shop.
    All prices are in USD. Judge only from the data given below; never invent specifications.

    Product:
    - Name: {{ product.name }}
    - Category: {{ product.categoryName }}
    - Price: {{ product.price }}
    - Description: {{ product.description }}

    Task 1 - Description quality.
    Judge the product description by these criteria: specificity (key specifications for this
    product type), consistency with the product name, no unverifiable superlatives, adequate length,
    and readability. Map the result to a verdict and a score from 1 to 10. List concrete issues and
    actionable suggestions; use empty lists when there is nothing to report. Give a one-sentence summary.

    Task 2 - Category fit.
    Available categories:
    {% for category in categories %}
    - {{ category.name }}{{ ": " ~ category.description if category.description else "" }}
    {% endfor %}
    Judge whether the assigned category is the right one for this product. If the verdict is not FITS,
    suggestedCategory must be copied exactly from the list above, or be null when no listed category
    fits better.

    Task 3 - Price.
    First identify the product type and key specifications from the product's own name and
    description (pricedAs). Then estimate the typical current US retail price range for products of
    that SAME type and specifications, from general market knowledge. If the brand is unknown or
    unfamiliar, estimate from comparable products of the same type and specifications instead of
    giving up.
    estimatedMarketMinUsd and estimatedMarketMaxUsd must describe products of the same type as this
    product. They must NOT be derived from the catalog peer prices below.

    Catalog context (the other products in the assigned category; may be a different product type):
    {% if peerStats %}
    Catalog peers in {{ peerStats.categoryName }}: {{ peerStats.count }} products, min {{ peerStats.min }}, median {{ peerStats.median }}, max {{ peerStats.max }}.
    {% for peer in peers %}
    - {{ peer.name }}: {{ peer.price }}
    {% endfor %}
    {% else %}
    No other catalog products in this category.
    {% endif %}
    Peers only reflect the assigned category, which may be wrong. Ignore peers of a different product
    type entirely, for both the market range and the verdict. Use peers only as supporting evidence,
    and only when they are the same product type.
    Choose FAIR when the price is within the typical range, ABOVE_MARKET or BELOW_MARKET when it is
    outside it, and INSUFFICIENT_DATA only when the product type cannot be identified. Set the
    confidence accordingly: LOW is the right answer for an unfamiliar brand, not INSUFFICIENT_DATA.

    {{ ctx.output_format }}
  "#
}

test VistaraDemoOffer {
  functions [AnalyzeOffer]
  args {
    product {
      name #"Vistara 65" OLED 4K TV"#
      description "The best TV ever. Buy now!"
      price "USD 2799.00"
      categoryName "Smart Home"
    }
    categories [
      {
        name "Televisions"
        description "Smart TVs, OLED, QLED, and LED displays for home entertainment"
      }
      {
        name "Audio Systems"
        description "Soundbars, speakers, and home theater audio equipment"
      }
      {
        name "Smart Home"
        description "Smart assistants, hubs, sensors, and connected home devices"
      }
      {
        name "Networking"
        description "Routers, mesh systems, range extenders, and network switches"
      }
      {
        name "Home Appliances"
        description "Robot vacuums, air purifiers, and smart kitchen appliances"
      }
    ]
    peerStats {
      categoryName "Smart Home"
      count 3
      min "USD 179.99"
      median "USD 179.99"
      max "USD 279.99"
    }
    peers [
      {
        name #"Amazon Echo Hub 8" Smart Home Control Panel"#
        price "USD 179.99"
      }
      {
        name "Philips Hue Starter Kit E26"
        price "USD 179.99"
      }
      {
        name "Google Nest Learning Thermostat 4th Gen"
        price "USD 279.99"
      }
    ]
  }
  @@assert(tv_market_range, {{ this.price.estimatedMarketMinUsd > 500 }})
}
