# nlFilter Lab

稼働中の `nlFilters` を変更する前に、構文と代表ページへの適用結果をローカルで確認するための開発ツールです。JDK 17 と PowerShell だけで動作し、外部パッケージは使用しません。

## 構文チェック

リポジトリ直下で実行します。引数を省略すると、Git が追跡している `.txt` だけを辞書順で検査します。

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 check
.\tools\nlfilter-lab\nlfilter-lab.ps1 check .\20_watchFilter.txt
```

終了コードは、エラーなしなら `0`、構文エラーがあれば `1` です。警告だけでは失敗しません。

## ローカルテスター

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 serve
```

表示された `http://127.0.0.1:8765/` をブラウザーで開きます。ポートを変える場合は `serve --port 9000` のように指定します。

画面では次を切り替えられます。

- watch、検索、Nアニメ風の fixture
- 対象URLと Content-Type
- キャッシュなし、通常、エコノミー、DMC通常、DMCエコノミー
- 適用するフィルターファイル
- SPA風の後続DOM追加

適用したルール、変換前後のHTML、ページ内で発生した JavaScript の警告・エラーも確認できます。`/local/*` は実環境の `C:\NicoCache_nl\local` から読み取り専用で配信します。

プレビューiframeは sandbox と Content Security Policy で外部資産の取得、フォーム送信、ポップアップを遮断し、fixture内リンクの既定遷移も無効化します。既存の `overlib_mini.js` が文字列から関数を生成するため、プレビュー内に限って `unsafe-eval` を許可しています。ローカルAPIは同一オリジンとsandboxの `null` Originだけを受け付けます。

## 自動テスト

```powershell
.\tools\nlfilter-lab\nlfilter-lab.ps1 test
```

## 再現範囲

このツールは、現在の追跡フィルターで利用している主要機能を対象にした疑似実装です。NicoCache_nl 本体を起動せず、キャッシュやHTTP応答を変更しません。

対応対象:

- `[Replace]`、`[Script]`、`[Style]`
- `URL`、`FullURL`、`ContentType`、`Require`、`StatusCode`
- `Multi`、`EachLine`、`ReplaceDelay`、`ReplaceOnly`
- `idGroup` と5種類の疑似キャッシュ状態
- `$URLn`、`$TS(...)`、`<CRLF>`、`<TAB>`、キャッシュ種別分岐

静的検査のみ、または警告扱い:

- `[RequestHeader]`、`[Config]`
- `RequireHeader`、`MatchLocal`、`AddList`、`AddVariable`
- `$LST`、`$INC`、`$SET`、`<nlVar:...>` など状態を持つマクロ
- NicoCache_nl 拡張、実キャッシュ検索、実際のプロキシヘッダー

最終確認では、通常どおり NicoCache_nl 経由の対象ページでも動作を確認してください。
