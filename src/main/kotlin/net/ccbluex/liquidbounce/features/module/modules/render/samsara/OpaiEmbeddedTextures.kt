package net.ccbluex.liquidbounce.features.module.modules.render.samsara

/** Bundled fallback: source images remain in assets/samsara/textures. */
internal object OpaiEmbeddedTextures {
    fun base64(path: String): String? = when (path) {
        "hud/target-opai.png" ->
            "iVBORw0KGgoAAAANSUhEUgAAAigAAADgCAYAAAApKZ11AAASqUlEQVR4nO3d3assWXkH4Lf23mf8Co6JYjQQAuJA8EZEEhIvch/y" +
            "AYH8mbkK5B+IN5FETG4kxMEQkHEUNM5kdNRzzq7QsBYsFmtVVffu7lq9z/NA09VVvXdV97l4f+dda9WOAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAC4qum6p7sZvhcArmn2dT/PQvxcPgcAnMPNB55bLOy3eM0AsLc5bsgtFPtrXOMtfA8AvDnmZ3KOZ1WY" +
            "pxN/Zh708wDAtcxFTTzlZ4cx3fC1TDf2+QDg3OYzvecp77+IEQr4U4PGNOjnAoBrmTfu23LsmPdczDTwuaeN+7e+b+t5AWBE8xP3" +
            "H/vzW49fxF7F+ikdkaWA8tTwAgAjmjfun088tnSOtWMXMd1IMOkFj9ZxIQWA52TeuL8VQOrn3vEt51s7drMB5ZhwshQ8pg3He8eO" +
            "vS4A2Mu88dhSGCn3rR0/5dwXc63ifOx8ktbzqce2XMfW4wBwScfOB5kXnk89tuV65ud8E7Reh6MOHK3XW461zn1sYAGAPc0bX/fC" +
            "x3zCsfo8u4SUSxfoLUuAex2RXhiZVt7T2m7dzK11IxuBBYA9terSXByrX8fGENJ6tN5T/96la+vtO4tLFuRjVuPUYeOu2H/XOd4L" +
            "KuXPLs1ROfbaAeBSjrkvyVzty68fq33lo3XsceVnW9d2tZAy7RxO1rokdyv7y+Pldvnlbp2fAgAjmlee69r32Ngu97VCylJ3pTxX" +
            "vb2070mmncNJLASRXvgow8l9+h2vi/R32PciIj7TOV/vGtc+AwCMMDl2rp5/GREvUy2MTn3sBZdeSCm7K7uElGnnzkmvO5KHae6q" +
            "RxlMHtM/yCci4ncj4g8i4ssR8fsR8am0r5xzAgDPyZxq3P9GxMcR8ZOI+HFEvJf2/Sb9h/2uEVTKRxTbW+aslOdvXdPNBZS1cFKG" +
            "kPJ1+cjB5FVE/F5E/HFEfD0ivhgRn07vyV9yTpIA8JzdF3XyUP9+FRE/jYj/iIj/jIifR8RDFVTqR9k5WQoqS5Noe/t2DyinhpN6" +
            "PkkdSKbiy/9t6oz8SUR8MyI+V3RSHjes0jn3ZwaAa5kb+1qrfO6KzskvIuK7EfGvqavyVvGf+LkRWLbMW7lKSJmuHE6i0yVZ6pg8" +
            "FC2oP4uIv4iIz6aw8qqxWgcA3nRzejykUPJhRPxzRPxLOn6XauhSR6XVXcm/u/Vcn3/IgNJaQhydbkkZTu4b4eRl6pr8bRrSOYyp" +
            "CSYAcFxQ+UQa8vnH1E150Qgpr1e6KfVy5PI89Xl3DyhbuydrnZP74jmHk0OX5CsR8fcR8XaaBKRjAgDHyYHisIjkg4j4h4j4Yequ" +
            "5JDyeiGktFb9ROO5PudQAaU376Q3Afa+8XxIdb9OQzp/lT7ky2J1DwBwvMdUYw+1+J/SkM8ni2XKZUBpzU+p56YshZQnBZRDl+JS" +
            "QzuxslqnF07eSsM4fx4Rf5O6KIcvQzgBgKfJc0/u0tSJQ13+Thr+OdTbJY+NWl+GkHpxSm+xyuYLvYR6Dkp96/pyiCeHk4diPOyr" +
            "EfGXRTgxCRYAzmNKtfW3qdZ+NdXeF8Vy5PtOzY5qO/++sztnQFnqnuRz1cM8Zeckd3MOE2L/Lm0LJwBwuZASqeYeam+kWlyPbrRu" +
            "pFrX+PL37h5QehcxHXFTttaX8NdpQuxhPEznBAAuY0q19u1Ue1vNg7q5UD/y71nKBCe55LyOpVBy35gUe2gv/WlEvJNW65hzAgCX" +
            "dZdq7jupBuehnnpl7VJYudiFnUMrRbXuf1IHljy0M6db138rjYkJJwBwHflO7d9KtTjfN+V+oX4v1fyzhJZTg8DaydeGdsrHQ1rK" +
            "9I10h9hDegMArudVqsHfSDU5T5Zt/dHe3lBPz0mB5e6K3ZPWbezvU1L7fPqjf7onALBfF+XrqSbPjaGd+iarF+2iXGIoZW2SbDnx" +
            "Jv+dnXfSJB3dEwDYx6tUi99JtblcctzrnsSl5qJcKqDExuGdKd0c5muppWTVDgDsY0q1+GupNi/V7lZIGXaSbOumLa1JsuUN2g4t" +
            "pM+ldlL+A4AAwPVNqRZ/PtXmPMxT1u56eKd8ztu7TZJtBZFYmXtSh5PcMjr4UvrjRfmGMQDAPh5TTT7U5qiGd3r1vBdMlvLC1Yd4" +
            "6jTVG+IpP/AX0/aT/zQzAPAkc6rJX2zMG20N8cSlhnme+scCj52DUgeVw+svmH8CAEPNQ/lCyghl3a67KHVIOWuj4VzLjFtzUHpd" +
            "lPL1W+nPPOueAMAY5lSb31qp4bFQ/6++zHjrzVha4SQa808+nZY0+aOAALC/KdXkt1ONjmpaRjxhiGca5Y8FtuaftBKY7gkAjGVe" +
            "mabRqvUtJ3dSzjlJtrfUuD5fHVoAgPHcdZYXly6yxDif/KnWlhXVYaROYAe6KAAwhnmhXtehZUsWGOKvGZeve/NPtrSEAIB99Vbj" +
            "to7VPxfXDCitE64N6eT9Sw8AYDzTCfV7LRdMe96ordc96R0XUgBgLNOJNf1sTg0oWy+ilaSEEwAY37QQTurttd9ztGusolkLKQDA" +
            "eKYzhJMYZZlxud16LZQAwO2ZOnV8yy1Gdg8o5bKk2DBJRlgBgHFNC7W6V+vPdtuQuxsZvwIArmvXeaTnvA9K70LLNCWcAMDtmDrb" +
            "vU7J2YLLtSfJLu0DAMYzbdx3Vpe6D8rSfl0UALjtIZ7aMPdB2WLtQnVRAGBs0xOPDxdQWt0SAOD5mKrns7rmHJTeMA8AMK5pjzp+" +
            "jYACAHAUAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAE" +
            "FABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgA" +
            "wHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDh" +
            "CCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQ" +
            "AIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAA" +
            "wxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgAMR0ABAIYjoAAAwxFQAIDhCCgAwHAEFABgOAIKADAcAQUAGI6AAgC8kQFlLp7L" +
            "bQBgfPMedfxSAUUQAYDnba6eb6aDsnbBuigAMLb5iceHCShl+6e3vzwupADAmObG9pY6f3NzUNb2AQDjmTfuixEDylJymqr3tbYB" +
            "gPHMne1p4f3zLXRQ8oUuDfH0PiQAsI9pZYjn7EM6lwwoOWjMG1tDh/f/JiI+StehowIA+5pTTf4o1eg6qJTva72eRgwoddJqva5T" +
            "16uI+LUbxgHAMO5SbT7U6KUaHp3XZ7uIS1u68NcR8QvDPAAwjCnV5kONjr3mkZ4aULZe2NL41WP6En5ueAcAhjGn2jylWv3UW4XM" +
            "I90HpTcxtj52+PA/K8a5AID95PmhPyvmn2yt6bvdB2Vtkkzvwlof4LGaiPNBRNzrpADAbuZUiz+oFrA8HhFG1nLBvMd9UOrXrcRV" +
            "fsi8/TIi/jt9KQDAfu5TTX65ULdjJawMcx+UpQsrP9RjtS/vP3wZP4qIX1rNAwC7uUu1+EepNtedkyjq+drqnXm0ZcZrrZ06ieWJ" +
            "sv8XEe9GxAvDPABwdXOqwe+mmpwnyNZ1u/Vz5fYQy4yPnXPyWG3nx+t0HYcv5Ve6KABwdXepBr+btl836nVdz4+dk3L0BR1j7URL" +
            "M3zz6/oD50R2mJDzfV0UANile/L9VIujU6uXanv5vHSeq89BaQ3vtD5InbpyB+V1Gu/6r4h4T0gBgKuGk/dSDb4v6nKrZrfCSav+" +
            "DzUHJevN8G0N8eQvoPwi/i0iPrbsGACusqz441R7y8ZB+VwP8fRW6p7VJW7UVj/X81DKrkm5P9/z/zA55ztp283bAOAyco39Tqq9" +
            "kWpxGUjKbkpr/klcKqScElDWlhWVS5F6a6jrD54fr9I1vR8R/x4RD53zAACnyTX1IdXa91PtfdWoy63aXe/Lv3PttiNHOeeN2loX" +
            "UoeVOpw8NgLKy9Ry+kFEfC9dY76bHQBwurmoq99LtfY+1d46oOQa3Qon+XeVz3n7LPU6dyjOae7cvz/f86Qe6pmKLys/Dl/UW2m5" +
            "0+GmMd+MiE+l/YZ9AOD0CbGHOSffjYgfp3Dy20bnpDW001tinH/3WV1qkmxvomxvAk7rS3mZru8nEfHtiPhpCi05/AAA2xsHb6Va" +
            "+u1UW3NDoBdMWgtZeuHk7HV5OtPPTcVzfuRuyJQS2l16zo+H6vGi8Zwf+We/EhFfTd2UPJHnKZ8DAJ6jOT3fpZr6cRqV+GEROF4W" +
            "j1eN5/KxNPSzNHG2vp6LD/HkNLZ0vJWu8jDP1BjmaT2yF+n1D9Jknj+KiD+MiE8W81nqDy+0APAmmBv17yE9/zqFkv+pbmFfBpPX" +
            "jeetwztbuicndVeeUsTP0UVZ66bcF9t5fz7P70TElyLiy2k7D//krkrrbwYAwHNzVzzPaU7JR2mOyfvF3WHnqjuSh3e2dE2u2j25" +
            "dECJKqCUk2DLYJLbTw+NUNIKL3lfPsfhvZ+JiLcj4rMprLxIw0BrnR4AuFVzqnEfp4BxCCIfRsQHaYHJy+J9ZYekDiF1WMmPfI+y" +
            "3r1Qyj9Xc/aA8pRVPL3iny+kXMmT99dDPYcPHMVzdH42ip97SM85qLxO/yAfFuHnIXVUymRZBqfWdQsyAIxgXthX328sUsckB4l6" +
            "9KAMGPV23SkpX9d3kl0a2lka5plHWGZcB5bywsuhlxw+6gmuZWDpzV957PwNnxxMyvurvOzMaZmODCWCCwCXsKV4z43teeVRr5DN" +
            "262wUoeUpdU7rY7JWbol17oPSh1W8ofKY2NluitDyavqg9X3Uim/pBxM8hdY30clDymdElAEEgBG65wc+7fuHjeGlPI9dfek/p1l" +
            "56Z1XWf1cIGuSauLUm5vmbya33vf+ILy/JX8Bd435riUk3TrcNKbOwMAI5kbr1ujC3WHY+mO7a0Jr2sdlKVhnrXrHfZOsuVN1eqw" +
            "UnZTlt5z1/nyc+ckD/FMKx2UeMIwDwBc29zY7s3r7E2F6HVS8nbdRdk696R1PWd1joDSmyxbH8vDPHl/ft3qqMxF9+S+6p7U4eR1" +
            "J5iUy65iJaS0XgPAHubO6/q5vK1Gb6in7oKsdUrqcFKHlLJmL4WS+RbmoJSvy1BSLkWuP/DcCCn5iyk7Jo+d7kkZULbOQendQl9w" +
            "AeASejVn7ryn1T2JzjBP2UXpdVRaAaa1jLg36rH0OWKUgNLqorSGeuovvl7J02ofld2TMpSUQacXTtaWGG/polzkiwfgjbel5syN" +
            "7bpGroWUMpSUc1PK7VbXpO6e1NfQut7evqGWGcdKOMla3ZS5MwclH++t2Gmt3OkN7eiMAHAL5oXn+rE03FO/bk2AXZt70rqO1rXG" +
            "qEM8LUshJYovJAeP150vfm0y7NLKnbXuydJ8GgDYo07Oje06MPTmi/SGaspHnDAp9uIuUYzX7i3S6mS0Oh+95cJLk2FP6ZysfQcC" +
            "CwCXMJ9psuzcmci6NGTT2673tc7Xu/azhpdLFd9jQkq93Qsprf2tibDHBBThA4CRzScO9ZRzVHohZGl/+Xt719G7zrO4ZIHeGlKi" +
            "ESrK4NG6n8nSXJO7xjnK40vXd8xxANirkzI3jpWrbHpdlV6XpHU89gon1yjCayGlfN3rpLT2Lb2ntd2aY9Ia5xNKANhTqy7NxbH6" +
            "dX5e2l6bU7J1EuzVwsm1CnLvHGvdlHJ7OuFY69zHdlEAYE/zxtdrAeWYY/V5eiHkohNmr1Wgt4SU8nWvq3LKsS3XsfU4ANzSxNlT" +
            "jsXe4eTaBXnpXGtBpd63drx37NjrAoC9zBuPbVltszbhdWswWTt2NtcuzscGhbVOyJYwsrV7AwAjmjfu3xpalvYvnW/t2FntVaRP" +
            "DSqt48IJAM/dvHH/0tyRp6zGuVowGaGLcOp8kKd2SHROALhF8xP3nzqf5OrhZJRiPV244wIAz928cd+WY8e852JGKuTTlYMNANy6" +
            "+Uzvecr7L2LEAn7KNdU3YQOAN9HcuRHp1p8dxi0U9D1vJgcAe5ifyTneqMJ8i9cMAHub44Y8l2L/XD4HALxxYaRFYfe9ALC/mw8U" +
            "AAAAAAAAAAAAAEAs+3/dYUdRqNTCLwAAAABJRU5ErkJggg=="
        "hud/target-opai-bar.png" ->
            "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAYAAACNiR0NAAAA8UlEQVR4nLWVUQrCMBBEpyrSI3gEr+OHIPTb8/QavYK38EPwAoI9" +
            "g9KMrExgTZVqUx8sSbeZYdm0CeAgOde4JLkneSB5IRkUF+Xs3dJrepCcadySPHIYW7P12neV1U7QKayySHD5SO09bLJIzO6J4BOd" +
            "1nrTp5dNNs80eUsqGiJIY2yiWUnyrOQ3laV0Gs2jtGZWANYAAoDX5n7HTFrzqOxhZ4UqxkLFriB5BbBSosgwLAC0ZphTWY8xPRs0" +
            "bDXP7aHRmuFJCdupsQR5nMywUUPHbgicvpn8w8Y/fr3FPw6HeWIaBb8fX5FJD1hnmn0FPAC9pwSKUyY/MQAAAABJRU5ErkJggg=="
        else -> null
    }
}
