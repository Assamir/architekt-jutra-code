import { Html, Head, Main, NextScript } from "next/document";

export default function Document() {
  return (
    <Html lang="en">
      <Head>
        <script src="http://localhost:8180/assets/plugin-sdk.js" />
        <link rel="stylesheet" href="http://localhost:8180/assets/plugin-ui.css" />
      </Head>
      <body>
        <Main />
        <NextScript />
      </body>
    </Html>
  );
}
