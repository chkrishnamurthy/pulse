# Ask Pulse: free chat on the phone (23 Nov 2026, Claude 1, asked for by the user)

| Part | How | Code |
|---|---|---|
| Understand the question | Plain rules | chat/QuestionRouter |
| Look up and calculate every number | Pulse's own code | chat/AnswerBuilder |
| Emergency, medical and off-topic questions | Fixed messages | QuestionRouter, ui/screens/ChatScreen |
| Switch areas off, keep or delete chats | You, then Ask Pulse | ui/screens/ChatSettingsScreen, chat/ChatStore |

No AI. Chat makes no internet request: it reads the phone's own database. Chats are not kept unless switched on.

## The AI was tried and left out (the user's choice)

Google's Gemma 4 E2B was tested on the user's S24 Ultra with 30 made-up questions: load 4.9 to 10.8 s, first word 0.45 to
0.8 s, about 830 MB memory. It was fast, but it invented numbers ("515 minutes"; "50000" from a trick question). A safer
design, where the AI only explains in words, was built. Its second test never finished because the phone left USB, so it was never
proven. The user then chose to skip the AI. The code is in git history (commit c9db9fd: LocalModel, ModelStore,
AnswerGuard, ChatSetupScreen). Without the AI library the release build went from 29 MB to 5.4 MB.

## Bugs found on the emulator and fixed

| ID | Bug | Fix |
|---|---|---|
| C-1 | The year in a date was ignored ("2 January 2020" answered as 2026) | Year is read; tests added |
| C-2 | "New chat" stayed scrolled down and kept the keyboard open | Scrolls to the top, closes the keyboard |
| C-3 | Resting heart rate bars looked flat, then a 2 bpm difference looked huge | Scale covers at least 15% of the value |
| C-4 | Comparing steps showed 11,606 in the heading and 11,373 in the sentence, and the difference was 1 off | Heading, bars and sentence use the same seven full days; differences use the numbers as shown; test added |
| C-5 | A lower resting heart rate than normal got "take an easier day" | The idea now fits the result; test added |

## Checked

134 unit tests (the AI's own tests went with it), lint 0 errors, debug and release builds. Emulator script: 32 of 32 checks in light and dark,
0 crashes, no AI text left. This was on the project including another session's uncommitted work (PersonalScreens, calendar goals).

## On the phone

Installed on the S24 Ultra on 23 Nov 2026 at 22:55 as an update (same signature, file identical to the tested build, data kept). It starts
without a crash. The phone was locked, so the chat with real data has not been looked at. The AI test app and the 2.6 GB file were
removed; Pulse has no AI file of its own.
